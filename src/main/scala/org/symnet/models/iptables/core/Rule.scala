// Copyright (C) 2017 Calin Cruceru <calin.cruceru@stud.acs.upb.ro>.
//
// See the LICENCE file distributed with this work for additional
// information regarding copyright ownership.

package org.symnet.models.iptables.core

class Rule(matches: List[Match], target: Target)

object Rule {
  def apply(matches: List[Match], target: Target): Rule =
    new Rule(matches, target)
}
