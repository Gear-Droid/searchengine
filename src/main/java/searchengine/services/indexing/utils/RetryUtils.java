package searchengine.services.indexing.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RetryUtils {

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void withRetry(int maxTimes, long intervalWait, ThrowingRunnable runnable) throws Exception {
        if (maxTimes <= 0) throw new IllegalArgumentException("Количество повторных запусков должно быть от 1!");
        if (intervalWait <= 0) throw new IllegalArgumentException("Время ожидания должно быть от 1!");

        int retryCounter = 0;
        Exception thrown = null;
        while (retryCounter <= maxTimes) {
            try {
                if(retryCounter > 0) log.debug("Повторная попытка вызова! " + runnable);

                runnable.run();
                return;
            } catch (Exception e) {
                thrown = e;
                retryCounter++;
            }

            try {
                Thread.sleep(intervalWait);
            } catch (InterruptedException wakeAndAbort) {
                break;
            }
        }
        throw thrown;
    }
}