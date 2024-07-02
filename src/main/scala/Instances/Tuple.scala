package Instances

import Types.CRDT

given [A1, A2, B1, B2, C](using
    x: CRDT[A1, B1, C],
    y: CRDT[A2, B2, C]
): CRDT[(A1, A2), (B1, B2), C] with
  def bottom(procID: C): (A1, A2) = (
    summon[CRDT[A1, B1, C]].bottom(procID),
    summon[CRDT[A2, B2, C]].bottom(procID)
  )
  extension (x: (A1, A2)) def read(): (B1, B2) = (x._1.read(), x._2.read())
  extension (x: (A1, A2))
    def \/(y: (A1, A2)): (A1, A2) = (x._1 \/ y._1, x._2 \/ y._2)
