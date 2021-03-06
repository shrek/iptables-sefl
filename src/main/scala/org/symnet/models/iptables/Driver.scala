// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet
package models.iptables

// Scala
import scala.io.Source

// 3rd-party
// -> scallop
import org.rogach.scallop._

// -> Symnet
import org.change.v2.analysis.expression.concrete.ConstantValue
import org.change.v2.analysis.memory.TagExp._
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.util.canonicalnames._

// project
import core._
import extensions.conntrack.ConnectionState
import types.net.Ipv4
import virtdev.devices.IPTRouterBuilder
import virtdev.SymnetFacade

class Driver(
    name: String,
    ipsStr: String,
    routingTableStr: String,
    iptablesStr: String,
    inputPort: String,
    validateOnly: Boolean = false,
    initInstruction: Instruction = NoOp) extends SymnetFacade with BaseParsers {
  // We need the check-fail function for parsing any type T.
  import Driver.parse

  // Set the identifier of this device.
  override def deviceId: String = name

  lazy val ipsMap =
    ipsStr.split("\n").filter(!_.trim.isEmpty).map(line => {
      val tokens = line.trim.split(" ")
      val int = parse(identifierParser, tokens(0))
      // FIXME: Multiple ips on an interface.
      (int, parse(ipParser, tokens(1)))
    }).toMap

  lazy val routingTable =
    routingTableStr.split("\n").filter(!_.trim.isEmpty).map(line => {
        val Array(ipStr, nextHop) = line.trim.split(" ")
        (parse(ipParser, ipStr), parse(identifierParser, nextHop))
    }).toList

  lazy val iptables = {
    implicit val parsingContext = ParsingContext.default
    val parsedTables = parse(many(iptParsers.tableParser), iptablesStr)

    val vParsedTables =
      parsedTables.flatMap(_.validate(ValidationContext.empty).toOption)
    assert(vParsedTables.size == parsedTables.size)

    vParsedTables
  }

  lazy val iptRouter =
    new IPTRouterBuilder(deviceId, ipsMap, routingTable, iptables).build

  def run() =
    if (validateOnly) {
      // Force the evaluation of all of the above lazy vals.
      ipsMap; routingTable; iptables; iptRouter;
      (Nil, Nil)
    } else {
      // Run symbolic execution starting on the specified input port.
      symExec(
        iptRouter,
        iptRouter.inputPort(inputPort),
        otherInstr = initInstruction,
        log = true
      )
    }
}

object Driver extends App with BaseParsers {

  /////////////////////////////////////////
  /// Utility (private) functions
  /////////////////////////////////////////

  def parse[T](p: Parser[T], s: String): T = {
    val maybeResult = p.apply(s)
    assert(maybeResult.isJust)

    val (state, result) = maybeResult.toOption.get
    assert(state.trim.isEmpty)

    result
  }

  /////////////////////////////////////////
  /// Parse args
  /////////////////////////////////////////

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val iptables = opt[String](required = true)
    val routing_table = opt[String](required = true)
    val ips = opt[String](required = true)
    val input_port = opt[String](required = true)
    val validate_only = opt[Boolean]()
    val source_ip = opt[String]()
    val destination_ip = opt[String]()

    validateOpt (validate_only, input_port) {
      case (Some(true), None) => Right(Unit)
      case (Some(false), Some(port)) => Right(Unit)
      case _ => Left("Either `validate_only' or `input_port' must be specified")
    }
    verify()
  }
  val conf = new Conf(args)

  /////////////////////////////////////////
  /// Build the driver and run it.
  /////////////////////////////////////////

  val List(iptables, routingTable, ips) =
    List(conf.iptables(), conf.routing_table(), conf.ips()) map {
      fileName => Source.fromFile(fileName).getLines.mkString("\n")
    }
  val driver = new Driver(
      name = "ipt-router",
      ipsStr = ips,
      routingTableStr = routingTable,
      iptablesStr = iptables,
      inputPort = conf.input_port(),
      validateOnly = conf.validate_only(),
      // Maybe use a destination ip address, if specified as argument.
      initInstruction = InstructionBlock(
        conf.source_ip.toOption match {
          case Some(ip) => Assign(IPSrc, ConstantValue(parse(ipParser, ip).host))
          case None     => NoOp
        },
        conf.destination_ip.toOption match {
          case Some(ip) => Assign(IPDst, ConstantValue(parse(ipParser, ip).host))
          case None     => NoOp
        }))

  // NOTE: Not the most accurate way of measuring time while running on a JVM,
  // but our benchmark framework is too slow.
  val t0 = System.nanoTime()
  val (successful, failed) = driver.run()
  val t1 = System.nanoTime()


  /////////////////////////////////////////
  /// Print various useful statistics.
  /////////////////////////////////////////

  println("*********** STATS BEGIN HERE ***********")
  println("Symbolic execution time: %.2fs".format((t1 - t0) / 1000000000.0))

  val iptRouter = driver.iptRouter
  val localSuccessCount = successful.filter(
    _.history.head == iptRouter.localProcessInputPort).size

  // Print the number of packets that reached the local process.
  println(s"Successful paths: ${successful.size}")
  println(s"Failed paths: ${failed.size}")
  println(s"Paths reaching input port: $localSuccessCount")

  println("*********** STATS END HERE ***********")
}
