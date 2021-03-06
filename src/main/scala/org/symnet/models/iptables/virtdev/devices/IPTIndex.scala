// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet
package models.iptables.virtdev
package devices

import models.iptables.core._

final class IPTIndex(iptables: List[Table]) {

  ///
  /// Miscellanea
  ///
  private val tablesOrdering: List[String] =
    List("raw", "mangle", "nat", "filter")

  private def sortByTablesOrdering(lhs: Table, rhs: Table) =
    tablesOrdering.indexOf(lhs.name) < tablesOrdering.indexOf(rhs.name)

  val allChains: List[Chain] =
    iptables.sortWith(sortByTablesOrdering).flatMap(_.chains)

  val chainsToTables: Map[Chain, Table] =
    allChains.map(c => c -> iptables.find(_.chains.contains(c)).get).toMap

  val userChains: List[UserChain] =
    allChains.collect { case chain: UserChain => chain }

  val builtinChains: List[BuiltinChain] =
    allChains.collect { case chain: BuiltinChain => chain }

  /** Collects the chains with the given name from all tables. */
  def chainsByName(chainName: String): List[Chain] =
    allChains.filter(_.name == chainName)

  ///
  /// Quick access to relations between chains given by jumps.
  ///

  type AdjacencyLists = Map[Chain, Set[Chain]]

  // TODO: Skip those with `goto' set to true; however, only do this once
  // everything else is tested, to make sure it doesn't break anything.
  val outAdjacencyLists: AdjacencyLists = allChains.map(chain => chain ->
    chain.rules.flatMap(_.target match {
      case uc: UserChain => Some(uc: Chain)
      case _             => None
    }).toSet).toMap

  val inAdjacencyLists:  AdjacencyLists = allChains.map(chain => chain ->
    outAdjacencyLists.flatMap { case (c, neighs) =>
      if (neighs.contains(chain)) Some(c) else None }.toSet).toMap

  ///
  /// Split a chain's rules between the rules which, if matches, jump to another
  /// chain.

  /** A rule is a 'boundary rule' if it jumps to a user defined chain. */
  private def isBoundaryRule(rule: Rule): Boolean = rule.target match {
      case uc: UserChain => true
      case _             => false
  }

  val chainsSplitSubrules: Map[Chain, List[List[Rule]]] = allChains.map(chain =>
    chain -> {
      val rules = chain.rules

      if (rules.isEmpty) {
        Nil
      } else {
        // Get a list of indices of the rules which might jump to user-defined
        // chains.
        val indices =
          (-1) +:
          rules.init.zipWithIndex.filter(e => isBoundaryRule(e._1)).map(_._2) :+
          (rules.length - 1)

        // Use the list of indices to split the original list or rules into
        // (possibly) multiple sublists of rules, keeping the original ordering.
        (indices zip indices.tail) map {
          case (a, b) => rules.slice(a + 1, b + 1)
        }
      }
    }).toMap
}
