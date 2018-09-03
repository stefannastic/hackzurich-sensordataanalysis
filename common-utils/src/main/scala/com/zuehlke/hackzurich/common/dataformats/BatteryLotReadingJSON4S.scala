package com.zuehlke.hackzurich.common.dataformats

import org.apache.log4j.LogManager

import scala.util.control.NonFatal

// Hint: Use only lowercase characters in case classes to avoid trouble when storing data in Cassandra,
// where rules for column names my be different

/** See https://github.com/Zuehlke/hackzurich-sensordata-ios/blob/master/README.md#accelerometer */
case class BatteryLotReadingJSON4S(id: Int, date_observed: String, total_spot_number: Int, free_slot_number: Int, sensortype: String = "BicycleLot") extends Ordered[BatteryLotReadingJSON4S] {
  def toCsv: String = s"$id ; $date_observed ; $total_spot_number ; $free_slot_number "

  override def compare(that: BatteryLotReadingJSON4S): Int = this.date_observed.compareTo(that.date_observed)
}

object BatteryLotReadingJSON4S {

  import org.json4s._

  def from(t: (String, JValue)): Option[BatteryLotReadingJSON4S] = {
    implicit val formats = DefaultFormats
    try Some(BatteryLotReadingJSON4S(
      t._1.toInt,
      (t._2 \ "dateObserved").extract[String],
      (t._2 \ "totalSpotNumber").extract[Int],
      (t._2 \ "freeSlotNumber").extract[Int]))
      //(t._2 \ "location" \ "coordinates").extract[String],
      //(t._2 \ "location" \ "type").extract[String]))
    catch {
      case NonFatal(e) => LogManager.getLogger(BatteryLotReadingJSON4S.getClass).warn("Failed to get data from json. Possible wrong format: " + e); None
    }
  }

  def apply(csv: String): BatteryLotReadingJSON4S = {
    try {
      val split = csv.split(";").map(_.trim)
      val _id = split(0).toInt
      val _dateObserved = split(1)
      val _totalSpotNumber = split(2).toInt
      val _freeSlotNumber = split(3).toInt
     // val _locationCoordinates = split(4)
      //val _locationType = split(4)

      BatteryLotReadingJSON4S(_id, _dateObserved, _totalSpotNumber, _freeSlotNumber )
    } catch {
      case e: Exception => BatteryLotReadingJSON4S(-1, e.getMessage, -1, -1)
    }
  }
}