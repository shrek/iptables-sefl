// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet
package models.iptables
package extensions.nat

import org.change.v2.analysis.expression.concrete.{ConstantValue, SymbolicValue}
import org.change.v2.analysis.expression.concrete.nonprimitive.:@
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.util.canonicalnames.{IPDst, TcpDst}

import types.net.{Ipv4, PortRange}

import core._
import extensions.filter.ProtocolMatch

object DnatTargetExtension extends TargetExtension {
  val targetParser = DnatTarget.parser
}

case class DnatTarget(
    lowerIp:   Ipv4,
    upperIp:   Option[Ipv4],
    portRange: Option[PortRange]) extends Target {
  type Self = DnatTarget

  override protected def validateIf(context: ValidationContext): Boolean = {
    val table = context.table.get
    val chain = context.chain.get
    val rule = context.rule.get

    // Check the table/chain in which this target is valid.
    table.name == "nat" && (chain match {
      case _ @ BuiltinChain("PREROUTING", _, _) => true
      case _ @ BuiltinChain("OUTPUT", _, _) => true
      case _ @ UserChain(_, _) => true
      case _ => false
    }) &&
    // Check that 'tcp' or 'udp' is specified when given the port range.
    //
    // The existance of the port range implies that '-p tcp/udp' must
    // have been specified.
    (portRange.isEmpty || ProtocolMatch.ruleMatchesTcpOrUdp(rule))
  }

  override def seflCode(options: SeflGenOptions): Instruction = {
    // Get the name of the metadata tags.
    val fromIp = virtdev.dnatFromIp(options.deviceId)
    val fromPort = virtdev.dnatFromPort(options.deviceId)
    val toIp = virtdev.dnatToIp(options.deviceId)
    val toPort = virtdev.dnatToPort(options.deviceId)
    val dnatStateTag = virtdev.dnatStateTag(options.deviceId)

    // If the upper bound is not given, we simply constrain on [lower, lower].
    val (lower, upper) = (lowerIp, upperIp getOrElse lowerIp)

    InstructionBlock(
      // Save original addresses.
      Assign(fromIp, :@(IPDst)),
      Assign(fromPort, :@(TcpDst)),

      // Mangle IP address.
      Assign(IPDst, SymbolicValue()),
      Constrain(IPDst, :&:(:>=:(ConstantValue(lower.host)),
                           :<=:(ConstantValue(upper.host)))),

      // Mangle TCP/UDP port address.
      Assign(TcpDst, SymbolicValue()),
      if (portRange.isDefined) {
        // If a port range was specified, use it.
        val (lowerPort, upperPort) = portRange.get

        Constrain(TcpDst, :&:(:>=:(ConstantValue(lowerPort)),
                              :<=:(ConstantValue(upperPort))))
      } else {
        NoOp
      },

      // Save the new addresses.
      Assign(toIp, :@(IPDst)),
      Assign(toPort, :@(TcpDst)),

      // Activate the DNAT virtual state.
      Assign(dnatStateTag, ConstantValue(1)),

      // In the end, we accept the packet.
      Forward(options.acceptPort)
    )
  }
}

object DnatTarget extends BaseParsers {
  import ParserMP.monadPlusSyntax._

  def parser: Parser[Target] =
    for {
      _ <- iptParsers.jumpOptionParser

      // Parse the actual target.
      targetName <- someSpacesParser >> identifierParser if targetName == "DNAT"

      // Parse the mandatory '--to-destination' target option.
      _ <- someSpacesParser >> parseString("--to-destination")
      lowerIp <- someSpacesParser >> ipParser

      // Parse the optional upper bound ip.
      upperIp <- optional(parseChar('-') >> ipParser)

      // Parse the optional port range.
      maybePortRange <- optional(parseChar(':') >> portRangeParser)
    } yield DnatTarget(lowerIp, upperIp, maybePortRange)
}
