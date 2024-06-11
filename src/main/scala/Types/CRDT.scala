package Types

import org.apache.pekko
import pekko.actor.Actor
import pekko.actor.Props
import pekko.event.Logging

trait CRDT[A, B] extends Lattice[A]:
  def fork(a: A): A
  val pure: B => A
  def runCRDT(a: A): B
