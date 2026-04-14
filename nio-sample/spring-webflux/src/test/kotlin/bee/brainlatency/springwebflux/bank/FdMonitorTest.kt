package bee.brainlatency.springwebflux.bank

import bee.brainlatency.springwebflux.bank.application.BankService
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import reactor.core.publisher.Flux
import java.lang.management.ManagementFactory

@SpringBootTest
class FdMonitorTest(private val sut: BankService) : StringSpec({

    "check fd count while 2000 concurrent requests are in-flight" {
        val pid = ProcessHandle.current().pid()
        val orgCodes = (1..2_000).map { it.toString() }

        println("PID: $pid")
        println("FD before: ${fdCount()}")
        println("Run this while the test is paused:")
        println("  lsof -p $pid -i TCP | wc -l")

        // fire requests but don't block yet
        val mono = sut.getBanks(orgCodes)
        val disposable = mono.subscribe()

        // pause here — requests are in-flight, FDs are open
        println("--- requests in-flight, check FD now ---")
        Thread.sleep(500)
        println("FD during: ${fdCount()}")

        // wait for completion
        mono.block()
        println("FD after:  ${fdCount()}")
    }

}) {
    override fun extensions() = listOf(SpringExtension)
}

private fun fdCount(): Long {
    val os = ManagementFactory.getOperatingSystemMXBean()
    return if (os is com.sun.management.UnixOperatingSystemMXBean) os.openFileDescriptorCount else -1
}
