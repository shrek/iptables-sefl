// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet
package models.iptables.core

// scala
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.junit.JUnitRunner

// 3rd party:
// -> scalaz
import scalaz.Maybe._

// -> Symnet
import org.change.v2.analysis.processingmodels.Instruction

// project
import types.net.Ipv4
import Policy._

@RunWith(classOf[JUnitRunner])
class CoreValidationSuite extends FunSuite with Matchers {

  private val emptyCtx = ValidationContext.empty

  test("simple table validation") {
    // Success
    {
      val filterTable = Table("filter", Nil)
      filterTable.validate(emptyCtx) shouldBe Just(filterTable)
    }
    {
      val natTable = Table("nat", Nil)
      natTable.validate(emptyCtx) shouldBe Just(natTable)
    }
    {
      val mangleTable = Table("mangle", Nil)
      mangleTable.validate(emptyCtx) shouldBe Just(mangleTable)
    }
    {
      val rawTable = Table("raw", Nil)
      rawTable.validate(emptyCtx) shouldBe Just(rawTable)
    }

    // Failure
    {
      val almostFilterTable = Table("filtre", Nil)
      almostFilterTable.validate(emptyCtx) shouldBe empty
    }
  }

  test("table with chains validation") {
    // Success
    {
      val chains = List(
        BuiltinChain("FORWARD", Nil, Drop),
        BuiltinChain("INPUT", Nil, Drop)
      )
      val table = Table("filter", chains)

      table.validate(emptyCtx) shouldBe Just(table)
    }
    {
      // Capitalization matters.
      val chains = List(
        BuiltinChain("FORWARD", Nil, Drop),
        UserChain("forward", Nil)
      )
      val table = Table("filter", chains)

      table.validate(emptyCtx) shouldBe Just(table)
    }
    {
      // Order of chains in the list doesn't matter.
      val chains = List(
        UserChain("MY_CHAIN1", Nil),
        UserChain("MY_CHAIN3", Nil),
        BuiltinChain("OUTPUT", Nil, Return),
        BuiltinChain("POSTROUTING", Nil, Accept),
        UserChain("MY_CHAIN2", Nil)
      )
      val table = Table("nat", chains)

      table.validate(emptyCtx) shouldBe Just(table)
    }

    // Failure
    {
      // The 'POSTROUTING' chain cannot be part of the filter table.
      val chains = List(
        BuiltinChain("FORWARD", Nil, Drop),
        BuiltinChain("INPUT", Nil, Drop),
        BuiltinChain("POSTROUTING", Nil, Drop)
      )
      val table = Table("filter", chains)

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // A (builtin) chain cannot appear multiple times in the same table.
      val chains = List(
        BuiltinChain("INPUT", Nil, Drop),
        BuiltinChain("INPUT", Nil, Drop)
      )
      val table = Table("filter", chains)

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // A (user-defined) chain cannot appear multiple times in the same table.
      val chains = List(
        UserChain("MY_CHAIN", Nil),
        UserChain("MY_CHAIN", Nil)
      )
      val table = Table("filter", chains)

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // We cannot name a user-defined chain using one of the reserved names for
      // builtin chains.
      val chains = List(
        BuiltinChain("FORWARD", Nil, Drop),
        UserChain("INPUT", Nil)
      )
      val table = Table("mangle", chains)

      table.validate(emptyCtx) shouldBe empty
    }
  }

  // is always valid
  object validMatch extends Match {
    type Self = this.type

    // this is not used here
    override def seflCondition(options: SeflGenOptions): SeflCondition =
      SeflCondition.empty
  }
  // is always valid
  object validTarget extends Target {
    type Self = this.type

    // this is not used here
    override def seflCode(options: SeflGenOptions): Instruction = null
  }
  // is never valid
  object invalidMatch extends Match {
    type Self = this.type

    override protected def validateIf(context: ValidationContext): Boolean =
      false

    // this is not used here
    override def seflCondition(options: SeflGenOptions): SeflCondition =
      SeflCondition.empty
  }
  // is never valid
  object invalidTarget extends Target {
    type Self = this.type

    override protected def validateIf(context: ValidationContext): Boolean =
      false

    // this is not used here
    override def seflCode(options: SeflGenOptions): Instruction = null
  }

  test("1 table/1 chain/1 rule") {
    // Success
    {
      val rule = Rule(List(NegatedMatch(validMatch)), validTarget)
      val chain = BuiltinChain("FORWARD", List(rule), Drop)
      val table = Table("filter", List(chain))

      table.validate(emptyCtx) shouldBe Just(table)
    }
    {
      val rule  = Rule(List(validMatch), PlaceholderTarget("MY_CHAIN"))
      val myChain = UserChain("MY_CHAIN", Nil)
      val chain = BuiltinChain("FORWARD", List(rule), Drop)
      val table = Table("filter", List(chain, myChain))

      // The model after the rule is validate(emptyCtx)d.
      val vRule = Rule(List(validMatch), myChain)
      val vChain = BuiltinChain("FORWARD", List(vRule), Drop)
      val vTable = Table("filter", List(vChain, myChain))

      table.validate(emptyCtx) shouldBe Just(vTable)
      vTable should not equal (table)
    }

    // Failure
    {
      // Jumps to builtin chains are not allowed.
      val rule  = Rule(List(validMatch), PlaceholderTarget("FORWARD"))
      val myChain = UserChain("MY_CHAIN", List(rule))
      val chain = BuiltinChain("FORWARD", Nil, Drop)
      val table = Table("filter", List(chain, myChain))

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // Recursive jumps are not allowed.
      val rule  = Rule(List(validMatch), PlaceholderTarget("MY_CHAIN"))
      val chain = UserChain("MY_CHAIN", List(rule))
      val table = Table("filter", List(chain))

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // Invalid match.
      val rule = Rule(List(invalidMatch), validTarget)
      val chain = BuiltinChain("FORWARD", List(rule), Drop)
      val table = Table("filter", List(chain))

      table.validate(emptyCtx) shouldBe empty
    }
    {
      // Invalid target.
      val rule = Rule(List(NegatedMatch(validMatch)), invalidTarget)
      val chain = BuiltinChain("FORWARD", List(rule), Drop)
      val table = Table("filter", List(chain))

      table.validate(emptyCtx) shouldBe empty
    }
  }

  test("jump to user-defined chain") {
    // Success
    {
      val dstChain = UserChain("MY_CHAIN", Nil)
      val rule = Rule(List(validMatch), /* the target */ dstChain)
      val chain = BuiltinChain("PREROUTING", List(rule), Accept)

      // The order of chains is not important.
      val table1 = Table("nat", List(chain, dstChain))
      table1.validate(emptyCtx) shouldBe Just(table1)

      val table2 = Table("nat", List(dstChain, chain))
      table2.validate(emptyCtx) shouldBe Just(table2)
    }

    // Failure
    {
      // The destination chain is not part of the table.
      val dstChain = UserChain("MY_CHAIN", Nil)
      val rule = Rule(List(validMatch), /* the target */ dstChain)
      val chain = BuiltinChain("INPUT", List(rule), Accept)
      val table = Table("nat", List(chain))

      table.validate(emptyCtx) shouldBe empty
    }
  }

  test("prerouting chain in filter table is invalid") {
    val table = Table("filter", List(BuiltinChain("PREROUTING", Nil, Drop)))
    table.validate(emptyCtx) shouldBe empty
  }

  test("indirected jump should still replace PlaceholderTarget") {
    val rule1 = Rule(List(validMatch), PlaceholderTarget("CHAIN2"))
    val rule2 = Rule(List(validMatch), PlaceholderTarget("CHAIN3"))
    val chain1 = UserChain("CHAIN1", List(rule1))
    val chain2 = UserChain("CHAIN2", List(rule2))
    val chain3 = UserChain("CHAIN3", Nil)
    val table = Table("filter", List(chain1, chain2, chain3))

    val maybeValidTable = table.validate(emptyCtx)
    maybeValidTable shouldBe a [Just[_]]

    val validTable = maybeValidTable.toOption.get
    validTable.chains(0).name shouldBe "CHAIN1"
    validTable.chains(0).rules(0).target shouldBe a [UserChain]

    val ucTarget = validTable.chains(0).rules(0).target.asInstanceOf[UserChain]
    ucTarget.rules(0).target shouldBe a [UserChain]
  }

  test("negation is kept after validation too") {
    val rule = Rule(List(NegatedMatch(validMatch)), validTarget)
    val chain = BuiltinChain("FORWARD", List(rule), Drop)
    val table = Table("filter", List(chain))

    table.validate(emptyCtx) shouldBe Just(table)
  }

  test("raw validation") {
    val tableStr = """
      <<raw>>
      <PREROUTING:ACCEPT>
      -j neutron-l3-agent-PREROUTING
      <OUTPUT:ACCEPT>
      -j neutron-l3-agent-OUTPUT
      <neutron-l3-agent-OUTPUT>
      <neutron-l3-agent-PREROUTING>
    """
    implicit val context = ParsingContext.default

    val maybeTable = iptParsers.tableParser.eval(tableStr)
    maybeTable shouldBe a [Just[_]]

    val table = maybeTable.toOption.get
    val maybeVTable = table.validate(ValidationContext.empty)
    maybeVTable shouldBe a [Just[_]]
  }

  test("mangle validation") {
    val tableStr = """
      <<mangle>>
      <PREROUTING:ACCEPT>
      -j neutron-l3-agent-PREROUTING
      <INPUT:ACCEPT>
      -j neutron-l3-agent-INPUT
      <FORWARD:ACCEPT>
      -j neutron-l3-agent-FORWARD
      <OUTPUT:ACCEPT>
      -j neutron-l3-agent-OUTPUT
      <POSTROUTING:ACCEPT>
      -j neutron-l3-agent-POSTROUTING
      <neutron-l3-agent-FORWARD>
      <neutron-l3-agent-INPUT>
      <neutron-l3-agent-OUTPUT>
      <neutron-l3-agent-POSTROUTING>
      -o qg-09d66f0a-46 -m connmark --mark 0x0/0xffff0000 -j CONNMARK --save-mark --nfmask 0xffff0000 --ctmask 0xffff0000
      <neutron-l3-agent-PREROUTING>
      -j neutron-l3-agent-mark
      -j neutron-l3-agent-scope
      -m connmark ! --mark 0x0/0xffff0000 -j CONNMARK --restore-mark --nfmask 0xffff0000 --ctmask 0xffff0000
      -j neutron-l3-agent-floatingip
      -d 169.254.169.254/32 -i qr-+ -p tcp -m tcp --dport 80 -j MARK --set-xmark 0x1/0xffff
      <neutron-l3-agent-float-snat>
      -m connmark --mark 0x0/0xffff0000 -j CONNMARK --save-mark --nfmask 0xffff0000 --ctmask 0xffff0000
      <neutron-l3-agent-floatingip>
      <neutron-l3-agent-mark>
      -i qg-09d66f0a-46 -j MARK --set-xmark 0x2/0xffff
      <neutron-l3-agent-scope>
      -i qr-6a98a347-87 -j MARK --set-xmark 0x4000000/0xffff0000
      -i qg-09d66f0a-46 -j MARK --set-xmark 0x4000000/0xffff0000
    """
    implicit val context = ParsingContext.default

    val maybeTable = iptParsers.tableParser.eval(tableStr)
    maybeTable shouldBe a [Just[_]]

    val table = maybeTable.toOption.get
    val maybeVTable = table.validate(ValidationContext.empty)
    maybeVTable shouldBe a [Just[_]]
  }

  test("nat validation") {
    val tableStr = """
      <<nat>>
      <PREROUTING:ACCEPT>
      -j neutron-l3-agent-PREROUTING
      <INPUT:ACCEPT>
      <OUTPUT:ACCEPT>
      -j neutron-l3-agent-OUTPUT
      <POSTROUTING:ACCEPT>
      -j neutron-l3-agent-POSTROUTING
      -j neutron-postrouting-bottom
      <neutron-l3-agent-OUTPUT>
      <neutron-l3-agent-POSTROUTING>
      <neutron-l3-agent-PREROUTING>
      <neutron-l3-agent-float-snat>
      <neutron-l3-agent-snat>
      <neutron-postrouting-bottom>
    """
    implicit val context = ParsingContext.default

    val maybeTable = iptParsers.tableParser.eval(tableStr)
    maybeTable shouldBe a [Just[_]]

    val table = maybeTable.toOption.get
    val maybeVTable = table.validate(ValidationContext.empty)
    maybeVTable shouldBe a [Just[_]]
  }
}
