package Instances

import Types.CRDT

// newtype GSet a = Set a
type GSet[A] = Set[A]

object GSet:
  def newGSet[A](s: Set[A]):GSet[A] = s

// instance CRDT (GSet a) a b where
/**
  * Grow-only Set
  *
  * @return
  */
given [A, C] : CRDT[GSet[A], Set[A], C] with
    def bottom(procID: C): GSet[A] = Set.empty

    extension (x: GSet[A])
        def \/(y: GSet[A]): GSet[A] = x union y

    extension (x: GSet[A])
        def read(): Set[A] = x