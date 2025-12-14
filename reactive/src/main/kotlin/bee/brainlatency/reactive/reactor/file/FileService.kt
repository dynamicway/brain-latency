package bee.brainlatency.reactive.reactor.file

import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Path

class FileService {
    private val scheduler = Schedulers.newBoundedElastic(100, 100, "FileServiceScheduler")

    fun read(name: String): Mono<String> {
        return Mono.fromCallable { readFile(name) }
            .subscribeOn(scheduler)
    }

    private fun readFile(name: String): String {
        run {
            Thread.sleep(2000)
            return Files.readString(Path.of(name))
        }
    }

    fun write(name: String, content: String): Mono<Unit> {
        return Mono.fromRunnable<Unit>
        { Files.write(Path.of(name), content.toByteArray()) }
            .subscribeOn(scheduler)
    }

    fun delete(name: String): Mono<Unit> {
        return Mono.fromRunnable<Unit> {
            Files.delete(Path.of(name))
        }
            .subscribeOn(scheduler)
    }

}