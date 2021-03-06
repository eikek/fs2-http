package spinoco.fs2.http

import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext

import fs2.Stream._
import fs2.io.tcp.Socket
import fs2.util.Async
import fs2.util.syntax._
import fs2.{Stream, _}
import scodec.bits.ByteVector
import spinoco.fs2.crypto.io.tcp.TLSSocket
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.http.{HostPort, HttpScheme}
import spinoco.protocol.http.header.{HttpHeader, `Transfer-Encoding`}
import spinoco.fs2.http.util.chunk2ByteVector

import scala.concurrent.duration._
import scala.reflect.ClassTag


package object internal {

  val `\n` : ByteVector = ByteVector('\n')

  val `\r` : ByteVector = ByteVector('\r')

  val `\r\n`: ByteVector = ByteVector('\r','\n')

  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact



  /** yields to true, if chunked encoding header is present **/
  def bodyIsChunked(headers:List[HttpHeader]):Boolean = {
    headers.exists {
      case `Transfer-Encoding`(encodings) => encodings.exists(_.equalsIgnoreCase("chunked"))
      case _ => false
    }
  }

  /**
    * From the stream of bytes this extracts Http Header and body part.
    */
  def httpHeaderAndBody[F[_]](maxHeaderSize: Int): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    def go(self:Stream[F,Byte], buff: ByteVector): Stream[F, (ByteVector, Stream[F, Byte])] = {
      self.scope.uncons.flatMap {
        case None => Stream.fail(new Throwable(s"Incomplete Header received (sz = ${buff.size}): ${buff.decodeUtf8}"))
        case Some((chunk, next)) =>
          val bv = chunk2ByteVector(chunk)
          val all = buff ++ bv
          val idx = all.indexOfSlice(`\r\n\r\n`)
          if (idx < 0) {
            if (all.size > maxHeaderSize) Stream.fail(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else go(next, all)
          }
          else {
            val (h, t) = all.splitAt(idx)
            if (h.size > maxHeaderSize)  Stream.fail(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else Stream.emit((h, Stream.chunk(ByteVectorChunk(t.drop(`\r\n\r\n`.size))) ++ next))
          }
      }
    }

    go(_, ByteVector.empty)
  }


  /** evaluates address from the host port and scheme **/
  def addressForRequest[F[_]](scheme: HttpScheme.Value, host: HostPort)(implicit F: Async[F]):F[InetSocketAddress] = F.delay {
    val port = host.port.getOrElse {
      scheme match {
        case HttpScheme.HTTPS | HttpScheme.WSS => 443
        case HttpScheme.HTTP | HttpScheme.WS => 80
      }
    }

    new InetSocketAddress(host.host, port)
  }

  /** swaps header `H` for new value. If header exists, it is discarded. Appends header to the end**/
  def swapHeader[H <: HttpHeader](header: H)(headers: List[HttpHeader])(implicit CT: ClassTag[H]) : List[HttpHeader] = {
    headers.filterNot(CT.runtimeClass.isInstance) :+ header
  }

  /**
    * Reads from supplied socket with timeout until `shallTimeout` yields to true.
    * @param socket         A socket to read from
    * @param timeout        A timeout
    * @param shallTimeout   If true, timeout will be applied, if false timeout won't be applied.
    * @param chunkSize      Size of chunk to read up to
    */
  def readWithTimeout[F[_]](
    socket: Socket[F]
    , timeout: FiniteDuration
    , shallTimeout: F[Boolean]
    , chunkSize: Int
  )(implicit F: Async[F]) : Stream[F, Byte] = {
    def go(remains:FiniteDuration) : Stream[F, Byte] = {
      eval(shallTimeout).flatMap { shallTimeout =>
        if (!shallTimeout) socket.reads(chunkSize, None)
        else {
          if (remains <= 0.millis) Stream.fail(new TimeoutException())
          else {
            eval(F.delay(System.currentTimeMillis())).flatMap { start =>
            eval(socket.read(chunkSize, Some(remains))).flatMap { read =>
            eval(F.delay(System.currentTimeMillis())).flatMap { end => read match {
              case Some(bytes) => Stream.chunk(bytes) ++ go(remains - (end - start).millis)
              case None => Stream.empty
            }}}}
          }
        }
      }
    }

    go(timeout)
  }

  /** creates a function that lifts supplied socket to secure socket **/
  def liftToSecure[F[_]](sslStrategy: => Strategy, sslContext: => SSLContext, clientMode: Boolean)(socket: Socket[F])(implicit F: Async[F]): F[Socket[F]] = {
    F.delay {
      val ctx = sslContext.createSSLEngine()
      if (clientMode) ctx.setUseClientMode(true)
      ctx
    }
    .flatMap { jengine =>
      TLSSocket(socket, jengine)(F, sslStrategy).map { x => x:Socket[F] }
    }
  }

}
