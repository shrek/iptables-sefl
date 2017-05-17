// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet.models.iptables.core

import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions.{:~:, Constrain, ConstrainNamedSymbol, ConstrainRaw}

import scalaz.Maybe
import scalaz.Maybe._

trait Match extends IptElement {
  type Self <: Match

  /** A match could enable other match extensions.  This method returns them as
   *  a list.
   */
  def extensionsEnabled: List[MatchExtension] = Nil

  ///
  /// Sefl code generation
  ///

  /** Generates SEFL constraints corresponding to its semantics. */
  def seflConstrain(options: SeflGenOptions): Option[Instruction]
}

case class NegatedMatch(m: Match) extends Match {
  type Self = NegatedMatch

  override def validate(context: ValidationContext): Maybe[NegatedMatch] =
    m.validate(context).map(vM => NegatedMatch(vM))

  override def seflConstrain(options: SeflGenOptions): Option[Instruction] =
    m.seflConstrain(options) match {
      // TODO: Duplicate code, not nice :(.
      case Some(ConstrainNamedSymbol(what, withWhat, _)) =>
        Some(Constrain(what, :~:(withWhat)))
      case Some(ConstrainRaw(what, withWhat, _)) =>
        Some(Constrain(what, :~:(withWhat)))

      case i @ _ => i
    }
}

object Match {
  def maybeNegated[A](m: Match, o: Option[A]): Match =
    if (o.isDefined) NegatedMatch(m) else m
}
