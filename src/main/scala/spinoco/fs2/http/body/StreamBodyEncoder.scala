package spinoco.fs2.http.body

import fs2._
import fs2.util.Catchable
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}


trait StreamBodyEncoder[F[_], A] {
  /** an pipe to encode stram of `A` to stream of bytes **/
  def encode: Pipe[F, A, Byte]

  def contentType: ContentType

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapIn[B](f: B => A): StreamBodyEncoder[F, B] =
    StreamBodyEncoder(contentType) { _ map f through encode }

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapInF[B](f: B => F[A]): StreamBodyEncoder[F, B] =
    StreamBodyEncoder(contentType) { _ evalMap  f through encode }

  /** changes content type of this encoder **/
  def withContentType(tpe: ContentType): StreamBodyEncoder[F, A] =
    StreamBodyEncoder(tpe)(encode)

}

object StreamBodyEncoder {

  def apply[F[_], A](tpe: ContentType)(pipe: Pipe[F, A, Byte]): StreamBodyEncoder[F, A] =
    new StreamBodyEncoder[F, A] {
      def contentType: ContentType = tpe
      def encode: Pipe[F, A, Byte] = pipe
    }

  /** encoder that encodes bytes as they come in, with `application/octet-stream` content type **/
  def byteEncoder[F[_]] : StreamBodyEncoder[F, Byte] =
    StreamBodyEncoder(ContentType.BinaryContent(MediaType.`application/octet-stream`, None)) { identity }

  /** encoder that encodes ByteVector as they come in, with `application/octet-stream` content type **/
  def byteVectorEncoder[F[_]] : StreamBodyEncoder[F, ByteVector] =
    StreamBodyEncoder(ContentType.BinaryContent(MediaType.`application/octet-stream`, None)) { _.flatMap { bv => Stream.chunk(ByteVectorChunk(bv)) } }

  /** encoder that encodes utf8 string, with `text/plain` utf8 content type **/
  def utf8StringEncoder[F[_]](implicit F: Catchable[F]) : StreamBodyEncoder[F, String] =
    byteVectorEncoder mapInF[String] { s =>
      ByteVector.encodeUtf8(s) match {
        case Right(bv) => F.pure(bv)
        case Left(err) => F.fail[ByteVector](new Throwable(s"Failed to encode string: $err ($s) "))
      }
    } withContentType ContentType.TextContent(MediaType.`text/plain`, Some(MIMECharset.`UTF-8`))

  /** a convenience wrapper to convert body encoder to StreamBodyEncoder **/
  def fromBodyEncoder[F[_], A](implicit E: BodyEncoder[A]):StreamBodyEncoder[F, A] =
    StreamBodyEncoder(E.contentType) { _.flatMap { a =>
      E.encode(a) match {
        case Failure(err) => Stream.fail(new Throwable(s"Failed to encode: $err ($a)"))
        case Successful(bytes) => Stream.chunk(ByteVectorChunk(bytes))
      }
    }}



}
