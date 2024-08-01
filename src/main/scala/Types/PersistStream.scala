package Types

trait PersistStream[S, A]:
  extension (x: S) def next: (Option[A], S)
  def empty: S
