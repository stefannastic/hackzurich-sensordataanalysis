package com.zuehlke.hackzurich

import java.util.Properties

import com.datastax.spark.connector.SomeColumns
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.streaming._
import com.zuehlke.hackzurich.common.dataformats._
import com.zuehlke.hackzurich.common.kafkautils.{MesosKafkaBootstrapper, MessageStream, Topics}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.InputDStream


/**
  * Consumes messages from one or more topics in Kafka and puts them into a cassandra table + publish to Kafka
  *
  * Run in dcos with:
  *
  * dcos spark run --submit-args="--supervise --class com.zuehlke.hackzurich.KafkaToAccelerometer <jar_location>"
  */
object KafkaToBatteryLot {

  val KAFKA_PUBLISH_SIZE = 100

  def main(args: Array[String]) {
    val executionName = "KafkaToBatteryLot"

    val sparkConf = new SparkConf(true)
      .set("spark.cassandra.connection.host", "node-0-server.cassandra.autoip.dcos.thisdcos.directory,node-1-server.cassandra.autoip.dcos.thisdcos.directory,node-2-server.cassandra.autoip.dcos.thisdcos.directory")
      .set("spark.streaming.backpressure.enabled", "true")
      .set("spark.streaming.kafka.consumer.poll.ms", "4096")
      .set("spark.locality.wait", "6s")
    val spark = SparkSession.builder()
      .appName(executionName)
      .config(sparkConf)
      .getOrCreate()

    val connector = CassandraConnector(sparkConf)
    try {
      val session = connector.openSession()
      CassandraCQL.createSchema(session)
    }
    catch {
      case e: Exception => System.err.println("Could not create Schema: " + e)
    }

    // Kafka Properties
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", MesosKafkaBootstrapper.mkBootstrapServersString)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

    // Create context with 40 second batch interval
    val ssc = new StreamingContext(spark.sparkContext, Seconds(40))

    val messages: InputDStream[ConsumerRecord[String, String]] = MessageStream.directMessageStream(ssc, executionName)

    val keyFilter = MessageStream.filterKey()

    val parsedMessages = messages
      .filter(keyFilter(_))
      .flatMap(SensorReadingJSON4SParser.parseWithJson4s).cache()

    // save BatteryLot
    val batteryLotFilter = new SensorTypeFilter("BatteryLot")
    val parsedBatteryLotMessages = parsedMessages
      .filter(batteryLotFilter(_))
      .flatMap(BatteryLotReadingJSON4S.from)
    parsedBatteryLotMessages
      .saveToCassandra("sensordata", "batterylot", SomeColumns("id", "date_observed", "total_spot_number", "free_slot_number", "location_coordinates", "location_type"))

    // publish latest accelerometer data to Kafka for the data-analytics application
    parsedBatteryLotMessages.foreachRDD { rdd =>
      rdd.take(KAFKA_PUBLISH_SIZE).foreach { batteryLot =>
        new KafkaProducer[String, String](producerProps).send(new ProducerRecord(Topics.SENSOR_READING_ACCELEROMETER, batteryLot.deviceid, batteryLot.toCsv))
      }
    }

    // Start the computation
    ssc.start()
    ssc.awaitTermination()
  }
}