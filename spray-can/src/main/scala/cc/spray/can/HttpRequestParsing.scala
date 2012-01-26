/*
 * Copyright (C) 2011, 2012 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can

import config.HttpParserConfig
import java.nio.ByteBuffer
import model.{RequestLine, HttpHeader, HttpResponse}
import nio._
import annotation.tailrec
import parsing._
import util.Logging

object HttpRequestParsing extends Logging {

  def apply(config: HttpParserConfig)
           (innerPipeline: HttpMessagePartCompletedState ~~> HttpResponseRenderingContext)
           :ByteBuffer ~~> HttpResponseRenderingContext = {

    val StartRequestParser = new EmptyRequestParser(config)
    var currentParsingState: ParsingState = StartRequestParser

    ctx => {
      parse(ctx.input)

      @tailrec
      def parse(buffer: ByteBuffer) {
        currentParsingState match {
          case x: IntermediateState =>
            currentParsingState = x.read(buffer)
            currentParsingState match {
              case x: IntermediateState => // wait for more input

              case x: HttpMessagePartCompletedState =>
                dispatch(x)
                if (buffer.remaining > 0) parse(buffer) // there might be more input in the buffer, so recurse

              case x: ErrorState => handleParseError(x)
            }
          case x: ErrorState => // if we are already in the errorstate we ignore all further input
        }
      }

      def dispatch(state: HttpMessagePartCompletedState) {
        currentParsingState = state match {
          case _: HttpMessageCompletedState => StartRequestParser
          case _ => new ChunkParser(config)
        }
        innerPipeline(ctx.withInput(state))
      }

      def handleParseError(state: ErrorState) {
        log.warn("Illegal request, responding with status {} and '{}'", state.status, state.message)
        val response = HttpResponse(
          status = state.status,
          headers = List(HttpHeader("Content-Type", "text/plain"))
        ).withBody(state.message)

        // In case of a request parsing error we probably stopped reading the request somewhere in between, where we
        // cannot simply resume. Resetting to a known state is not easy either, so we need to close the connection to do so.
        // This is done here by pretending the request contained a "Connection: close" header
        ctx.push(HttpResponseRenderingContext(response, RequestLine(), Some("close")))
      }
    }
  }
}