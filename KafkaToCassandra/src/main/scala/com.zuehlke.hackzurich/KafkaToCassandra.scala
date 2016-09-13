package com.zuehlke.hackzurich

import com.datastax.spark.connector.SomeColumns
import com.datastax.spark.connector.streaming._
import com.zuehlke.hackzurich.common.dataformats.{BatteryReading, GyrometerReading, SensorReadingJSONParser, SensorTypeFilter}
import com.zuehlke.hackzurich.common.kafkautils.MessageStream
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming._


/**
  * Consumes messages from one or more topics in Kafka and puts them into a cassandra table
  *
  * Run in dcos with:
  *
  * dcos spark run --submit-args="--supervise ---class com.zuehlke.hackzurich.KafkaToCassandra <jar_location>"
  */
object KafkaToCassandra {

  def main(args: Array[String]) {
    val executionName = "KafkaToCassandra"

    val spark = SparkSession.builder()
      .appName(executionName)
      .config("spark.cassandra.connection.host", "node-0.cassandra.mesos,node-1.cassandra.mesos,node-2.cassandra.mesos")
      .getOrCreate()

    // Create context with 5 second batch interval
    val ssc = new StreamingContext(spark.sparkContext, Seconds(5))

    val messages = MessageStream.directMessageStream(ssc, executionName)
    // More config options:, Topics.SENSOR_READING, OffsetRestConfig.Earliest)

    val keyFilter = MessageStream.filterKey

    val parsedMessages = messages
      .filter(keyFilter(_))
      .flatMap(SensorReadingJSONParser.parseReadingsUsingScalaJSONParser)

    // save Gyro
    val gyroFilter = new SensorTypeFilter("Gyro")
    parsedMessages
      .filter(gyroFilter(_))
      .map(t => GyrometerReading(
        t._1,
        t._2.get("date").get.asInstanceOf[String],
        t._2.get("x").get.asInstanceOf[Double],
        t._2.get("y").get.asInstanceOf[Double],
        t._2.get("z").get.asInstanceOf[Double]))
     .saveToCassandra("sensordata", "gyro", SomeColumns("date", "deviceid", "x", "y", "z"))

    // save Battery
    val batteryFilter = new SensorTypeFilter("Battery")
    val batteryReadings = parsedMessages
      .filter(batteryFilter(_))
      .map(t => BatteryReading(
        t._1,
        t._2.get("date").get.asInstanceOf[String],
        t._2.get("batteryState").get.asInstanceOf[String],
        t._2.get("batteryLevel").get.asInstanceOf[Double]))

    batteryReadings
      .saveToCassandra("sensordata", "batteryhistory", SomeColumns("date", "deviceid", "batterystate", "batterylevel"))
    batteryReadings
      .saveToCassandra("sensordata", "batterycurrent", SomeColumns("date", "deviceid", "batterystate", "batterylevel"))

    // Start the computation
    ssc.start()
    ssc.awaitTermination()
  }
}