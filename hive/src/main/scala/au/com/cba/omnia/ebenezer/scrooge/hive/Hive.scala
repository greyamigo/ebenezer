//   Copyright 2014 Commonwealth Bank of Australia
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package au.com.cba.omnia.ebenezer.scrooge.hive

import scala.collection.JavaConverters._
import scala.util.{Failure, Try, Success}
import scala.util.control.NonFatal

import java.util.ArrayList

import scalaz._, Scalaz._
import scalaz.\&/.These

import cascading.tap.hive.HiveTableDescriptor

import com.twitter.scrooge.ThriftStruct

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.metastore.{HiveMetaHookLoader, HiveMetaStoreClient, IMetaStoreClient, RetryingMetaStoreClient}
import org.apache.hadoop.hive.metastore.api.{Database, Table, AlreadyExistsException, NoSuchObjectException}
import org.apache.hadoop.hive.ql.Driver
import org.apache.hadoop.hive.ql.session.SessionState

import au.com.cba.omnia.omnitool.Result

/**
  * A data-type that represents a Hive operation.
  *
  * Hive operations use a HiveConf as context, and produce a (potentially failing) result. For
  * convenience Hive operations receive both a HiveConf and a handle to a Hive client. The client
  * is created from the HiveConf when the run method is called.
  * 
  */
// NB that this is the Hive equivalent of the HDFS monad in permafrost.
case class Hive[A](action: (HiveConf, IMetaStoreClient) => Result[A]) {
  /** Map across successful Hive operations. */
  def map[B](f: A => B): Hive[B] =
    andThen(f andThen Result.ok)

  /** Bind through successful Hive operations. */
  def flatMap[B](f: A => Hive[B]): Hive[B] =
    Hive((conf, client) => action(conf, client).flatMap(f(_).action(conf, client)))

  /** Chain an unsafe operation through a successful Hive operation. */
  def safeMap[B](f: A => B): Hive[B] =
    map(f).safe

  /** Chain a context free result (i.e. requires no configuration) to this Hive operation. */
  def andThen[B](f: A => Result[B]): Hive[B] =
    flatMap(a => Hive((_, _) => f(a)))

  /** Convert this to a safe Hive operation that converts any exceptions to failed results. */
  def safe: Hive[A] =
    Hive((conf, client) => try {
      action(conf, client)
    } catch {
      case NonFatal(t) => Result.exception(t)
    })

  /**
    * Set the error message in a failure case. Useful for providing contextual information without
    * having to inspect result.
    * 
    * NB: This discards any existing message.
    */
  def setMessage(message: String): Hive[A] =
    Hive((conf, client) => action(conf, client).setMessage(message))

  /**
    * Adds an additional error message. Useful for adding more context as the error goes up the stack.
    * 
    * The new message is prepended to any existing message.
    */
  def addMessage(message: String, separator: String = ": "): Hive[A] =
    Hive((conf, client) => action(conf, client).addMessage(message))

  /**
    * Runs the first Hive operation. If it fails, runs the second operation. Useful for chaining optional operations.
    *
    * Throws away any error from the first operation.
    */
  def or(other: => Hive[A]): Hive[A] =
    Hive((conf, client) => action(conf, client).fold(Result.ok, _ => other.action(conf, client)))

  /** Alias for `or`. Provides nice syntax: `Hive.create("bad path") ||| Hive.create("good path")` */
  def |||(other: => Hive[A]): Hive[A] =
    or(other)

  /** Recovers from an error. */
  def recoverWith(recovery: PartialFunction[These[String, Throwable], Hive[A]]): Hive[A] =
    Hive((conf, client) => action(conf, client).fold(
      res   => Result.ok(res),
      error => recovery.andThen(_.action(conf, client)).applyOrElse(error, Result.these)
    ))

  /** Like "finally", but only performs the final action if there was an error. */
  def onException[B](action: Hive[B]): Hive[A] =
    this.recoverWith { case e => action >> Hive.result(Result.these(e)) }

  /** Ensures an action is run after this no matter whether this succeeds or fails. Generalizes "finally". */
  def ensuring[B](sequel: Hive[B]): Hive[A] = for {
    r <- onException(sequel)
    _ <- sequel
  } yield r

  /** Runs the Hive action with a RetryingMetaStoreClient created based on the provided HiveConf. */
  def run(hiveConf: HiveConf): Result[A] = {
    try {
      val client = RetryingMetaStoreClient.getProxy(
        hiveConf,
        new HiveMetaHookLoader() {
          override def getHook(tbl: Table) = null
        },
        classOf[HiveMetaStoreClient].getName()
      )

      try {
        val result = action(hiveConf, client)
        result
      } catch {
        case NonFatal(t) => Result.error("Failed to run hive operation", t)
      } finally {
        client.close
      }
    } catch {
      case NonFatal(t) => Result.error("Failed to create client", t)
    }
  }
}

/** Hive operations */
// NB that this is the Hive equivalent of the HDFS monad in permafrost.
object Hive {
  /** Build a HIVE operation from a result. */
  def result[A](v: Result[A]): Hive[A] =
    Hive((_, _) => v)

  /** Build a failed HIVE operation from the specified message. */
  def fail[A](message: String): Hive[A] =
    result(Result.fail(message))

  /** Build a failed HIVE operation from the specified exception. */
  def exception[A](t: Throwable): Hive[A] =
    result(Result.exception(t))

  /** Build a failed HIVE operation from the specified exception and message. */
  def error[A](message: String, t: Throwable): Hive[A] =
    result(Result.error(message, t))

  /**
    * Fails if condition is not met
    *
    * Provided instead of [[scalaz.MonadPlus]] typeclass, as Hive does not
    * quite meet the required laws.
    */
  def guard(ok: Boolean, message: String): Hive[Unit] =
    result(Result.guard(ok, message))

  /**
    * Fails if condition is met
    *
    * Provided instead of [[scalaz.MonadPlus]] typeclass, as Hive does not
    * quite meet the required laws.
    */
  def prevent(fail: Boolean, message: String): Hive[Unit] =
    result(Result.prevent(fail, message))

  /**
    * Ensures a Hive operation returning a boolean success flag fails if unsuccessfull
    *
    * Provided instead of [[scalaz.MonadPlus]] typeclass, as Hive does not
    * quite meet the required laws.
    */
  def mandatory(action: Hive[Boolean], message: String): Hive[Unit] =
    action flatMap (guard(_, message))

  /**
    * Ensures a Hive operation returning a boolean success flag fails if succeesfull
    *
    * Provided instead of [[scalaz.MonadPlus]] typeclass, as Hive does not
    * quite meet the required laws.
    */
  def forbidden(action: Hive[Boolean], message: String): Hive[Unit] =
    action flatMap (prevent(_, message))

  /** Gets the Hive conf. */
  def getConf: Hive[HiveConf] =
    Hive((conf, _) => Result.ok(conf))

  /** Gets the Hive client. */
  def getClient: Hive[IMetaStoreClient] =
    Hive((_, client) => Result.ok(client))

  /** Gets the Hive conf and client. */
  def getConfClient: Hive[(HiveConf, IMetaStoreClient)] =
    Hive((conf, client) => Result.ok((conf, client)))

  /** Builds a Hive operation from a function. The resultant Hive operation will not throw an exception. */
  def withConf[A](f: HiveConf => A): Hive[A] =
    Hive((conf, _) => Result.safe(f(conf)))

  /** Builds a Hive operation from a function. The resultant Hive operation will not throw an exception. */
  def withClient[A](f: IMetaStoreClient => A): Hive[A] =
    Hive((_, client) => Result.safe(f(client)))

  /** Builds a Hive operation from a value. The resultant Hive operation will not throw an exception. */
  def value[A](v: => A): Hive[A] =
    withConf(_ => v)

  /**
    * Creates a database if it doesn't already exists. Returns false if the DB already exists.
    * 
    * WARNING: This method is not thread safe. If the same database or table is created at the same
    * time Hive handles it badly and throws an SQL integrity exception.
    */
  def createDatabase(
    database: String, description: String = "", location: Option[Path] = None, parameters: Map[String, String] = Map.empty
  ): Hive[Boolean] = {
    val db = new Database(database, description, location.cata(_.toString, null), parameters.asJava)
    Hive((_, client) => try {
      client.createDatabase(db)
      Result.ok(true)
    } catch {
      case _: AlreadyExistsException => Result.ok(false)
      case NonFatal(t)               => Result.error(s"Failed to create database $database", t)
    })
  }

  /**
    * Creates hive table with the specified hive storage format.
    * 
    * Returns true if the table was created. If the table with the same schema already exists it
    * returns false. If a table with the same name but different schema exists it returns a Hive error.
    *
    * WARNING: This method is not thread safe. If the same database or table is created at the same
    * time Hive handles it badly and throws an SQL integrity exception.
    *
    * @param database Name of the database. Will be created if not found.
    * @param table Name of table to create.
    * @param partitionColumns A list of the partition columns formatted as `[(name, type.)]`.
    *                         If empty unpartitioned table will be created.
    * @param location Optional location for the hive table. Not set by default.
    * @param format Storage format of the hive table.
    */
  def createTable[T <: ThriftStruct : Manifest](
    database: String, table: String, partitionColumns: List[(String, String)],
    location: Option[Path] = None, format: HiveStorageFormat
  ): Hive[Boolean] = {
    Hive.createDatabase(database) >>
    Hive.getConfClient >>= { case (conf, client) =>
      val fqLocation = location.map(FileSystem.get(conf).makeQualified(_))
      val tableDescriptor = Util.createHiveTableDescriptor[T](database, table, partitionColumns, format, fqLocation)

      try {
        client.createTable(tableDescriptor.toHiveTable)
        Hive.value(true)
      } catch {
        case _: AlreadyExistsException =>
          Hive.mandatory(
            existsTableStrict[T](database, table, partitionColumns, location, format),
            s"$database.$table already exists but has different schema."
          ).map(_ => false)
        case NonFatal(t)               => Hive.error(s"Failed to create table $database.$table", t)
      }
    }
  }

  /**
    * Creates hive text table
    *
    * WARNING: This method is not thread safe. If the same database or table is created at the same
    * time Hive handles it badly and throws an SQL integrity exception.
    *
    * @param database Name of the database. Will be created if not found.
    * @param table Name of table to create.
    * @param partitionColumns A list of the partition columns formatted as `[(name, type.)]`.
    *                         If empty unpartitioned table will be created.
    * @param location Optional location for the hive table. Not set by default.
    * @returns true if the table already exists false otherwise
    */
  def createTextTable[T <: ThriftStruct : Manifest](
    database: String, table: String, partitionColumns: List[(String, String)],
    location: Option[Path] = None, delimiter: String = TextFormat.DEFAULT_DELIMITER
  ): Hive[Boolean] = createTable(database, table, partitionColumns, location, TextFormat(delimiter))

  /**
    * Creates hive parquet table
    *
    * WARNING: This method is not thread safe. If the same database or table is created at the same
    * time Hive handles it badly and throws an SQL integrity exception.
    *
    * @param database Name of the database. Will be created if not found.
    * @param table Name of table to create.
    * @param partitionColumns A list of the partition columns formatted as `[(name, type.)]`.
    *                         If empty unpartitioned table will be created.
    * @param location Optional location for the hive table. Not set by default.
    * @returns true if the table already exists false otherwise
    */
  def createParquetTable[T <: ThriftStruct : Manifest](
    database: String, table: String, partitionColumns: List[(String, String)],
    location: Option[Path] = None
  ): Hive[Boolean] = createTable(database, table, partitionColumns, location, ParquetFormat)

  /** Checks if the named table exists. */
  def existsTable(database: String, table: String): Hive[Boolean] =
    withClient(_.tableExists(database, table))

  /** Checks if a table with the same name and schema already exists. */
  def existsTableStrict[T <: ThriftStruct : Manifest](
    database: String, table: String, partitionColumns: List[(String, String)],
    location: Option[Path] = None, format: HiveStorageFormat = ParquetFormat
  ): Hive[Boolean] = Hive((conf, client) => try {
    val actualTable     = client.getTable(database, table)
    val expectedTable =
      Util.createHiveTableDescriptor[T](database, table, partitionColumns, format, location)

    val sd = actualTable.getSd
    val fs = FileSystem.get(conf)

    val actualPath     = fs.makeQualified(new Path(sd.getLocation))
    val expectedPath   = fs.makeQualified(new Path(expectedTable.getLocation(conf.getVar(ConfVars.METASTOREWAREHOUSE))))
    val actualCols     = sd.getCols.asScala.map(c => (c.getName.toLowerCase, c.getType.toLowerCase)).toList
    val actualPartCols = actualTable.getPartitionKeys.asScala.map(c => (c.getName.toLowerCase, c.getType.toLowerCase)).toList
    val (expectedCols, expectedPartCols) =
      expectedTable.getColumnNames
        .zip(expectedTable.getColumnTypes)
        .map(x => (x._1.toLowerCase, x._2.toLowerCase))
        .toList
        .splitAt(expectedTable.getColumnNames.length - expectedTable.getPartitionKeys.length)

    val delimiterComparison = format match {
      case ParquetFormat => true
      case TextFormat(delimiter) => 
        sd.getSerdeInfo.getParameters.asScala.get("field.delim").exists(_ == delimiter)
    }

    Result.ok(
      actualTable.getTableType == expectedTable.toHiveTable.getTableType          &&
      actualPath               == expectedPath                                    &&
      actualCols               == expectedCols                                    &&
      actualPartCols           == expectedPartCols                                &&
      sd.getInputFormat        == expectedTable.toHiveTable.getSd.getInputFormat  &&
      sd.getOutputFormat       == expectedTable.toHiveTable.getSd.getOutputFormat &&
      delimiterComparison                    
    )
  } catch {
    case _: NoSuchObjectException => Result.ok(false)
    case NonFatal(t)              => Result.error(s"Failed to check strict existence of $database.$table", t)
  })

  /** Gets the on disk location of a Hive table. */
  def getPath(database: String, table: String): Hive[Path] = Hive((conf, client) =>
    try {
      val location = client.getTable(database, table).getSd.getLocation
      Result.ok(FileSystem.get(conf).makeQualified(new Path(location)))
    } catch {
      case _: NoSuchObjectException => Result.fail(s"Table $database.$table does not exist")
      case NonFatal(t)              => Result.error(s"Failed to get path for $database.$table", t)
    }
  )

  /** Runs the specified Hive query. Returns at most `maxRows` */
  def query(query: String, maxRows: Int = 100): Hive[List[String]] = Hive { (conf, _) =>
    SessionState.start(conf)
    SessionState.get().setIsSilent(true)
    val driver = new Driver(conf)
    try {
      driver.init()
      driver.setMaxRows(maxRows)
      val response = driver.run(query)
      if (response.getResponseCode() != 0)
        Result.fail(s"Error running query '$query'. ${response.getErrorMessage}")
      else {
        val results    = new ArrayList[String]()
        val gotResults = driver.getResults(results)
        if (gotResults) Result.ok(results.asScala.toList)
        else            Result.ok(List.empty[String])
      }
    } catch {
      case NonFatal(ex) => Result.error(s"Error trying to run query '$query'", ex)
    } finally {
      driver.destroy()
    }
  }

  /** Runs the specified Hive queries. Returns at most `maxRows` per query */
  def queries(queries: List[String], maxRows: Int = 100): Hive[List[List[String]]] = {
    val setup = Hive.getConf.flatMap(conf => Hive.value {
      SessionState.start(conf)
      SessionState.get().setIsSilent(true)
      val driver = new Driver(conf)
      driver.init()
      driver.setMaxRows(maxRows)
      driver
    })

    val cleanup = (driver: Driver) => Hive.value(driver.destroy)

    val body = (driver: Driver) => queries.traverse(query => {
      val runQuery = for {
        response <- Hive.value(driver.run(query))
        _        <- Hive.guard(response.getResponseCode == 0, response.getErrorMessage)
        results  <- Hive.value {
          val results    = new ArrayList[String]()
          val gotResults = driver.getResults(results)
          if (gotResults) results.asScala.toList
          else            List.empty[String]
        }
      } yield results

      runQuery.safe.addMessage("Error trying to run query '$query'")
    })

    for {
      driver  <- setup
      results <- body(driver) ensuring cleanup(driver)
    } yield results
  }

  implicit def HiveMonad: Monad[Hive] = new Monad[Hive] {
    def point[A](v: => A) = result(Result.ok(v))
    def bind[A, B](a: Hive[A])(f: A => Hive[B]) = a flatMap f
  }
}
