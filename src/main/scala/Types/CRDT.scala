package Types

trait CRDT[A]:
  /** Lattice join
    *
    * @param y
    * @return
    */
  extension (x: A) infix def \/(y: A): A
