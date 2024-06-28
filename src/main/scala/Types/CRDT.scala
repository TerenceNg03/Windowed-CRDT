package Types

import org.apache.pekko
import pekko.actor.Actor
import pekko.actor.Props
import pekko.event.Logging

trait CRDT[A, B, C]:
  /**
    * Lower bound of semi lattice
    *
    * @param x
    * @return
    */
  def bottom(procID: C): A
  /**
    * Read the value out
    *
    * @return
    */
  extension (x: A) def read(): B
  /**
    * Lattice join
    *
    * @param y
    * @return
    */
  extension (x: A) infix def \/(y: A): A
