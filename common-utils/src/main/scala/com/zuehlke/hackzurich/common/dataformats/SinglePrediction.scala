package com.zuehlke.hackzurich.common.dataformats

import java.text.SimpleDateFormat

import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._


case class SinglePrediction(id: String, timestamp: Long, prediction: Double, speedLayer: Boolean, batchLayer: Boolean, combined: Boolean) {
  private val timeString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp)

  def toJSON: String = {
    val json =
      ("id" -> id) ~
        ("timestamp" -> timestamp) ~
        ("timeString" -> timeString) ~
        ("prediction" -> prediction) ~
        ("speedLayer" -> speedLayer) ~
        ("batchLayer" -> batchLayer) ~
        ("combined" -> combined)
    compact(render(json))
  }

  override def toString: String = toJSON
}

