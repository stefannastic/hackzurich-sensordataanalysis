package com.zuehlke.hackzurich

import java.sql.Timestamp
import java.time.{ZoneId, ZonedDateTime}
import java.util.Properties

import com.cloudera.sparkts.models.ARIMA
import com.cloudera.sparkts.{DateTimeIndex, SecondFrequency,MinuteFrequency, TimeSeriesRDD}
import com.zuehlke.hackzurich.common.dataformats.BatteryLotPrediction
import com.zuehlke.hackzurich.common.kafkautils.Topics
import org.apache.commons.math3.exception.{MathIllegalArgumentException, NoDataException}
import org.apache.commons.math3.linear.SingularMatrixException
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions._

object PredictionImpl extends IPrediction {

  /**
    * How far in the future we want to predict
    */
  val forecastTime = 30

  /**
    * Topic used to publish the predictions
    */
  private val KAFKA_TOPIC = Topics.DATA_ANALYTICS

  /**
    * UserDefinedFunction to create a new Timestamp from given input columns
    */
  val toDateUdf: UserDefinedFunction = udf((year: Int, month: Int, day: Int, hour: Int, minute: Int) => {
    val timeFormatted = s"$year-$month-$day $hour:$minute:00.0"
    Timestamp.valueOf(timeFormatted)
  })

  override def performPrediction(dataFrame: DataFrame, producerProperties: Properties): Unit = {
    // Aggregate data to a precision of seconds and calculate the mean
    var observations = dataFrame.select("id", "date_observed", "free_slot_number")
    observations = observations.groupBy(
      year(col("date_observed")).alias("year"),
      month(col("date_observed")).alias("month"),
      dayofmonth(col("date_observed")).alias("day"),
      hour(col("date_observed")).alias("hour"),
      minute(col("date_observed")).alias("minute"),
      col("id")
    ).agg(mean("free_slot_number").as("free_slot_number_average"))

    // Add converted (aggregated) date back again
    observations = observations.withColumn("timestamp", toDateUdf.apply(col("year"), col("month"), col("day"), col("hour"), col("minute")))

    val firstDate: Timestamp = observations.select("timestamp").orderBy(asc("timestamp")).head().getAs[Timestamp]("timestamp")
    val lastDate: Timestamp = observations.select("timestamp").orderBy(desc("timestamp")).head().getAs[Timestamp]("timestamp")

    // Create a DateTimeIndex over the whole range of input data in seconds interval
    val zone = ZoneId.systemDefault()
    val dtIndex = DateTimeIndex.uniformFromInterval(
      ZonedDateTime.of(firstDate.toLocalDateTime, zone),
      ZonedDateTime.of(lastDate.toLocalDateTime, zone),
      new MinuteFrequency(1))

    // Align the data on the DateTimeIndex to create a TimeSeriesRDD
    val timeSeriesrdd = TimeSeriesRDD.timeSeriesRDDFromObservations(dtIndex, observations,
      "timestamp", "id", "free_slot_number_average")

    // Compute missing values using linear interpolation
    val interpolatedTimeSeriesrdd = timeSeriesrdd.fill("linear")

    val forecast = interpolatedTimeSeriesrdd.mapSeries { vector =>
      /*
    * If we are missing some data in the beginning or at the end (so everything we could not interpolate)
    * we just give the prediction based on the latest data we have (which is everything != NaN)
    */
      val doubles = vector.toArray.filter(!_.isNaN)
      val newVec = if (doubles.length > 0)
        new DenseVector(doubles)
      else
        new DenseVector(Array(0.0))

      var returnValue = newVec

      try {
        val arimaModel = ARIMA.fitModel(1, 1, 0, newVec)
        val forecasted = arimaModel.forecast(newVec, forecastTime)
        returnValue = new DenseVector(forecasted.toArray.slice(forecasted.size - forecastTime, forecasted.size))
      } catch {
        case e: NegativeArraySizeException => println(s"Error with array: $newVec - ${e.getMessage}")
          returnValue = new DenseVector(Array(0.0))
        case e: SingularMatrixException => println(s"The result matrix of the model was singular: ${e.getMessage}")
          returnValue = new DenseVector(Array(0.0))
        case e: NoDataException => println(s"There was no input data: ${e.getMessage}")
          returnValue = new DenseVector(Array(0.0))
        case e: MathIllegalArgumentException => println(s"Math exception: ${e.getMessage}")
          returnValue = new DenseVector(Array(0.0))
      }
      returnValue
    }

    forecast.foreach { x =>
      val predictionMessage = BatteryLotPrediction(lastDate.getTime, x._1, x._2.toArray)
      new KafkaProducer[String, String](producerProperties).send(new ProducerRecord(KAFKA_TOPIC, x._1, predictionMessage.toCsv))
    }
  }
}
