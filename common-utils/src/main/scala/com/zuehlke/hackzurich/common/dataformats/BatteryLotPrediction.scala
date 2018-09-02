package com.zuehlke.hackzurich.common.dataformats

import java.text.SimpleDateFormat

import scala.collection.mutable.ArrayBuffer


case class BatteryLotPrediction(timestamp: Long, id: String, values: Seq[Double]) {
  private val timeString = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(timestamp)

  def toCsv: String = {
    s"$timestamp ; $id ; ${values.mkString(" ; ")}"
  }

  override def toString: String = {
    s"$timeString $id [${values.mkString(" , ")}]"
  }
}

object BatteryLotPrediction {
  def apply(csv: String): BatteryLotPrediction = {
    try {
      val split = csv.split(";").map(_.trim)
      val _timestamp = split(0).toLong
      val _deviceid = split(1)
      val _values = new ArrayBuffer[Double]()
      for (v <- split.slice(2, split.length)) {
        _values += v.toDouble
      }
      BatteryLotPrediction(_timestamp, _deviceid, _values)
    } catch {
      case e: Exception => BatteryLotPrediction(-1, e.getMessage, Seq.empty[Double])
    }
  }
}