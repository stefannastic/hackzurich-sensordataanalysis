package com.zuehlke.hackzurich

import com.datastax.driver.core.Session

object CassandraCQL {

  def createSchema(s: Session): Unit = {
    s.execute("CREATE KEYSPACE IF NOT EXISTS sensordata WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 2 }")
    s.execute("CREATE TABLE IF NOT EXISTS sensordata.batterylot( id int, date_observed timestamp, total_spot_number int, free_slot_number int, location_coordinates text, location_type text, PRIMARY KEY (id, date_observed) ) WITH CLUSTERING ORDER BY (date_observed DESC)")
  }

}
