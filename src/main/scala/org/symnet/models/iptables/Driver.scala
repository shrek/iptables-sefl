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
    ipsStr: String,
    routingTableStr: String,
    iptablesStr: String,
    inputPort: String,
    validateOnly: Boolean = false) extends SymnetFacade with BaseParsers {
  override def deviceId: String = "ipt-router"

  def initInstruction: Instruction = NoOp

  def run() = {
    // Parse ips.
    val ipsMap =
      ipsStr.split("\n").filter(!_.trim.isEmpty).map(line => {
        val tokens = line.trim.split(" ")
        val int = parse(identifierParser, tokens(0))
        // FIXME: Multiple ips on an interface.
        (int, parse(ipParser, tokens(1)))
      }).toMap

    // Parse routing table.
    val routingTable =
      routingTableStr.split("\n").filter(!_.trim.isEmpty).map(line => {
          val Array(ipStr, nextHop) = line.trim.split(" ")
          (parse(ipParser, ipStr), parse(identifierParser, nextHop))
      }).toList

    // Parse and validate iptables.
    val iptables = {
      implicit val parsingContext = ParsingContext.default
      val parsedTables = parse(many(iptParsers.tableParser), iptablesStr)

      val vParsedTables =
        parsedTables.flatMap(_.validate(ValidationContext.empty).toOption)
      assert(vParsedTables.size == parsedTables.size)

      vParsedTables
    }

    if (!validateOnly) {
      val iptRouter =
        new IPTRouterBuilder(deviceId, ipsMap, routingTable, iptables).build

      // Run symbolic execution starting on the specified input port.
      symExec(
        iptRouter,
        iptRouter.inputPort(inputPort),
        otherInstr = initInstruction,
        log = true
      )
    } else {
      (Nil, Nil)
    }
  }

  private def parse[T](p: Parser[T], s: String): T = {
    val maybeResult = p.apply(s)
    assert(maybeResult.isJust)

    val (state, result) = maybeResult.toOption.get
    assert(state.trim.isEmpty)

    result
  }
}

object Driver extends App {
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val iptables = opt[String](required = true)
    val routing_table = opt[String](required = true)
    val ips = opt[String](required = true)
    val validate_only = opt[Boolean]()
    val input_port = opt[String]()

    validateOpt (validate_only, input_port) {
      case (Some(true), None) => Right(Unit)
      case (Some(false), Some(port)) => Right(Unit)
      case _ => Left("Either `validate_only' or `input_port' must be specified")
    }
    verify()
  }
  val conf = new Conf(args)

  val List(iptables, routingTable, ips) =
    List(conf.iptables(), conf.routing_table(), conf.ips()) map {
      fileName => Source.fromFile(fileName).getLines.mkString("\n")
    }

  new Driver(
      ipsStr = ips,
      routingTableStr = routingTable,
      iptablesStr = iptables,
      inputPort = conf.input_port(),
      validateOnly = conf.validate_only()) {

    override def initInstruction = InstructionBlock(
      // This is the sane default for any "initial" packet.
      Assign(ctstate, ConstantValue(ConnectionState.New.id)),

      // Constrain the destination IP.
      Assign(IPDst, ConstantValue(Ipv4(8, 8, 8, 8, None).host))
    )
  }.run()
}
