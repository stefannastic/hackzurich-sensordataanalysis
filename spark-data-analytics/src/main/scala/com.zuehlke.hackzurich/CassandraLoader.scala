package com.zuehlke.hackzurich

import org.apache.spark.sql.{DataFrame, SparkSession}

object CassandraLoader {

  def loadDataFrame(spark: SparkSession, id: String = null): DataFrame = {
    val dataFrame = spark
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map(
        "keyspace" -> "sensordata",
        "table" -> "batterylot"))
      .load()

    id match {
      case null => dataFrame
      case id => dataFrame.where(s"id = '$id'")
    }
  }

}
