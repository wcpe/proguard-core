package proguard.testutils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClassPoolBuilderTest : FunSpec({
    test("并发编译不会混用源文件") {
        val taskCount = 16
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(taskCount)

        try {
            val results = (0 until taskCount).map { index ->
                executor.submit(Callable {
                    start.await()
                    val className = "Parallel$index"
                    val (programClassPool, _) = ClassPoolBuilder.fromSource(
                        JavaSource("$className.java", "public class $className {}"),
                        initialize = false,
                    )
                    programClassPool.getClass(className)
                })
            }

            start.countDown()

            results.forEach {
                it.get(2, TimeUnit.MINUTES) shouldNotBe null
            }
        } finally {
            executor.shutdownNow()
        }
    }
})
