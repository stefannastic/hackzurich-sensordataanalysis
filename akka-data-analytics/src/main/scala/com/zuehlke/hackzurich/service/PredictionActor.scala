package com.zuehlke.hackzurich.service

import java.text.SimpleDateFormat

import akka.actor.Actor
import com.zuehlke.hackzurich.common.dataformats.{AccelerometerReadingJSON4S, Prediction, SinglePrediction}
import com.zuehlke.hackzurich.service.PredictionActor._
import com.zuehlke.hackzurich.service.SparkDataAnalyticsPollingActor.SparkAnalyticsData
import com.zuehlke.hackzurich.service.SpeedLayerKafkaPollingActor.SpeedLayerData

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


class PredictionActor extends Actor {
  private val sparkAnalyticsData = mutable.Map.empty[String, Prediction]
  private val speedLayerData = ArrayBuffer.empty[AccelerometerReadingJSON4S]
  private val speedLayerDataMap = mutable.Map.empty[String, ArrayBuffer[(Long, Double)]]

  private val timeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  private val TIME_THRESHOLD_MS = 20 * 60 * 1000 // 20 Minutes
  private val WEIGHT_SPARK = 0.4
  private val WEIGHT_SPEED_LAYER = 0.6

  /**
    * How far in the future we want to predict the speed Layer
    */
  val forecastTime = 5

  private def updateSpeedLayerMap(): Unit = {
    for (x <- speedLayerData) {
      val id = x.deviceid
      // Create a new ArrayBuffer if there is no data yet for a specific deviceid
      var lst = speedLayerDataMap.getOrElse(id, ArrayBuffer.empty[(Long, Double)])
      // we can add it to the end of the list, as we know that the timestamps are in increasing order
      lst += Tuple2(x.date, x.z)
      speedLayerDataMap += (id -> lst)
    }

    // filter out old entries
    for (device <- speedLayerDataMap.keySet) {
      var lst = speedLayerDataMap(device)
      lst = lst.filter(_._1 > System.currentTimeMillis() - TIME_THRESHOLD_MS)
      speedLayerDataMap += (device -> lst)
    }

    speedLayerData.clear()
  }

  /**
    * Forecasts `forecastTime` values based on linear Regression
    *
    * @param values y values
    * @return Sequence of Doubles (size `forecastTime`)
    */
  private def forecast(values: Seq[Double]): Seq[Double] = {
    require(values.length >= 2, "I cannot predict something from less than two values")

    val lastX = values.length.toDouble
    val range = 1.0 to(lastX, 1.0)
    val regression = new LinearRegression(range, values)
    val predictions = for (i <- lastX + 1 to(lastX + forecastTime, 1.0)) yield regression.predict(i)
    predictions
  }

  override def receive: Receive = {
    case x: SparkAnalyticsData =>
      sparkAnalyticsData ++= x.data
    case x: SpeedLayerData =>
      speedLayerData ++= x.data
      updateSpeedLayerMap()
    case RequestPrediction() =>
      sender ! predictionsToJSON
    case RequestSpeedLayerData() =>
      sender ! speedLayerDataToJSON
    case RequestSpeedLayerPrediction() =>
      sender ! forecastedSpeedLayerDataToJSON
    case RequestSparkDataAnalyticsPrediction() =>
      sender ! sparkDataToJSON
    case RequestCombinedPrediction() =>
      sender ! combinedForecastData
    case x => println(s"PredictionActor: I got a weird message: $x")
  }

  private def predictionsToJSON(): String = {
    s"[ $sparkDataToJSON , $forecastedSpeedLayerDataToJSON , $speedLayerDataToJSON ]"
  }

  private def sparkDataToJSON: String = {
    val predictions = sparkAnalyticsData.values.toList
    val singlePredictions = ArrayBuffer.empty[SinglePrediction]
    for (p <- predictions) {
      singlePredictions ++= predictionsToSinglePredictions(p, false, true, false)
    }
    singlePredictions.mkString("[ ", " , ", " ]")
  }

  private def predictionsToSinglePredictions(p: Prediction, speedLayer: Boolean, batchLayer: Boolean, combined: Boolean): Seq[SinglePrediction] = {
    var i = 0
    val buffer = ArrayBuffer.empty[SinglePrediction]
    for (value <- p.values) {
      val timeStamp = p.timestamp + 1000 * 60 // Add one minute as we predict in minute steps
      val singlePrediction = SinglePrediction(p.deviceid, timeStamp, value, speedLayer, batchLayer, combined)
      buffer ++= Seq(singlePrediction)
    }
    buffer.toList
  }

  private def speedLayerDataToJSON: String = {
    val singlePredictions = ArrayBuffer.empty[SinglePrediction]

    for (device <- speedLayerDataMap.keySet) {
      val p = speedLayerDataMap(device)
      for (value <- p) {
        singlePredictions ++= Seq(SinglePrediction(device, value._1, value._2, speedLayer = true, batchLayer = false, combined = false))
      }
    }

    singlePredictions.mkString("[ ", " , ", " ]")
  }

  private def forecastedSpeedLayerDataToJSON: String = {
    val singlePredictions = ArrayBuffer.empty[SinglePrediction]

    for (device <- speedLayerDataMap.keySet) {
      singlePredictions ++= speedLayerEntriesToList(device, speedLayerDataMap(device))
    }

    singlePredictions.mkString("[ ", " , ", " ]")
  }

  private def speedLayerEntriesToList(id: String, predictionBuffer: ArrayBuffer[(Long, Double)]): Seq[SinglePrediction] = {
    val singlePredictions = ArrayBuffer.empty[SinglePrediction]

    val values = for (x <- predictionBuffer) yield x._2
    val timestamps = for (x <- predictionBuffer) yield x._1
    if (timestamps.nonEmpty) {
      val lastTimestamp = timestamps.max
      val predictions = forecast(values)
      var i = 1000 * 60
      for (value <- predictions) {
        val timeStamp = lastTimestamp + i
        singlePredictions ++= Seq(SinglePrediction(id, timeStamp, value, speedLayer = true, batchLayer = false, combined = false))
      }
    }
    singlePredictions.toList
  }

  private def combinedForecastData: String = {
    val singlePredictions = ArrayBuffer.empty[SinglePrediction]

    for (device <- speedLayerDataMap.keySet ++ sparkAnalyticsData.keySet) {
      val speedData = speedLayerDataMap.get(device)
      val sparkData = sparkAnalyticsData.get(device)

      (sparkData, speedData) match {
        case (None, None) => "Nothing in both maps?"
        case (Some(spark), None) =>
          singlePredictions ++= predictionsToSinglePredictions(spark, speedLayer = false, batchLayer = true, combined = false)
        case (None, Some(speed)) =>
          singlePredictions ++= speedLayerEntriesToList(device, speed)
        case (Some(spark), Some(speed)) =>
          // create lists of predictions rounded to seconds, still we need milliseconds
          var i = 0
          val sparkPredictions = for (x <- spark.values) yield {
            val time = Math.round((spark.timestamp + i).toDouble / 1000) * 1000
            i += 1000 * 60
            (time, x)
          }
          val speedPredictions = for (x <- speed) yield {
            val time = Math.round(x._1.toDouble / 1000) * 1000
            (time, x._2)
          }

          // find predictions for the same timestamp
          for (p_spark <- sparkPredictions) {
            for (p_speed <- speedPredictions) {
              if (p_spark._1 == p_speed._1) {
                val valueSpark = p_spark._2
                val valueSpeedLayer = p_speed._2
                val timeStamp = p_speed._1
                val weightedValue = valueSpark * WEIGHT_SPARK + valueSpeedLayer * WEIGHT_SPEED_LAYER
                singlePredictions ++= Seq(SinglePrediction(device, timeStamp, weightedValue, speedLayer = false, batchLayer = false, combined = true))
              }
            }
          }
      }
    }
    singlePredictions.mkString("[ ", " , ", " ]")
  }

}


object PredictionActor {

  sealed trait PredictionActorRequests

  case class RequestPrediction() extends PredictionActorRequests

  case class RequestSpeedLayerData() extends PredictionActorRequests

  case class RequestSpeedLayerPrediction() extends PredictionActorRequests

  case class RequestSparkDataAnalyticsPrediction() extends PredictionActorRequests

  case class RequestCombinedPrediction() extends PredictionActorRequests

}