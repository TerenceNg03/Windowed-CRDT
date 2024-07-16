package Instances

import Types.PersistStream

case class ListStream[A](
    val l: List[A]
)

given [A]:PersistStream[ListStream[A], A] with
    extension (x: ListStream[A]) def next: (Option[A], ListStream[A]) =
        x.l match
            case x::xs => (Some(x), ListStream(xs))
            case Nil => (None, x)

    def empty = ListStream(List.empty)
        