package Instances

import Types.CRDT

// newtype GSet a = Set a
opaque type GSet[A] = Set[A]

// instance CRDT (GSet a) a b where
/**
  * Grow-only Set
  *
  * @return
  */
given [A, C] : CRDT[GSet[A], Set[A], C] with
    def newCRDT(x: Set[A])(procID: C): GSet[A] = x

    extension (x: GSet[A])
        def \/(y: GSet[A]): GSet[A] = x union y

    extension (x: GSet[A])
        def read(): Set[A] = x