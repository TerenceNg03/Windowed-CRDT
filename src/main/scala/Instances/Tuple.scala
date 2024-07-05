package Instances

import Types.CRDT

given [A1, A2](using
    x: CRDT[A1],
    y: CRDT[A2]
): CRDT[(A1, A2)] with
  extension (x: (A1, A2))
    def \/(y: (A1, A2)): (A1, A2) = (x._1 \/ y._1, x._2 \/ y._2)
