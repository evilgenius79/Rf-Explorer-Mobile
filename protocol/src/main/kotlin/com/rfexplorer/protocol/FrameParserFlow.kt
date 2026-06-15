package com.rfexplorer.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapt a stream of raw USB read chunks into a stream of parsed messages.
 *
 * Stateful: a fresh [FrameParser] is created per collection so partial frames carry
 * across chunk boundaries. The transport layer wires `Flow<ByteArray>` straight in.
 */
fun Flow<ByteArray>.parseRfeFrames(): Flow<RfeMessage> = flow {
    val parser = FrameParser()
    collect { chunk ->
        for (message in parser.parse(chunk)) {
            emit(message)
        }
    }
}
