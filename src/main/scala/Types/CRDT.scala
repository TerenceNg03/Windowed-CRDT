package Types

import org.apache.pekko
import pekko.actor.Actor
import pekko.actor.Props
import pekko.event.Logging

trait CRDT[A: Lattice, B]:
  extension (x: A) def fork(): A
  def pure(x: B): A
  extension (x: A) def run(): B
