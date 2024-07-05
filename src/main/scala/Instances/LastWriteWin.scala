package Instances

import Types.CRDT

import java.sql.Timestamp

case class LastWriteWin[A](
    val v: (Timestamp, A)
)

object LastWriteWin:
  def newLWW[A](x: A): LastWriteWin[A] =
    LastWriteWin((new Timestamp(System.currentTimeMillis()), x))

given [A]: CRDT[LastWriteWin[A]] with
  extension (x: LastWriteWin[A])
    def \/(y: LastWriteWin[A]): LastWriteWin[A] =
      if x.v._1.after(y.v._1) then x
      else y
