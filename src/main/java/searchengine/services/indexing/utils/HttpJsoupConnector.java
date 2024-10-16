package searchengine.services.indexing.utils;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import searchengine.dto.PageDto;

import java.io.IOException;

@Slf4j
public class HttpJsoupConnector {

    private static final int REQUEST_TIMEOUT = 1_000;  // (мс) таймаут перед запросами к ссылкам
    private static final String USER_AGENT = "SkillboxFinalTaskSearchBot";
    private static final String REFERER = "http://www.google.com";

    public PageDto getPageDtoFromLink(String link) {
        PageDto pageDto = new PageDto();
        pageDto.setPath(link);
        synchronized (this) {
            try {
                fillPageDto(pageDto);
                pageDto.setCode(200);
            } catch (HttpStatusException e) {
                log.warn(e.getMessage());
                pageDto.setCode(e.getStatusCode());
            } catch (IOException e) {
                log.warn(e.getMessage());
                pageDto.setCode(HttpStatus.NOT_FOUND.value());
            } finally {
                try {
                    Thread.sleep(REQUEST_TIMEOUT);
                } catch (InterruptedException e) {
                    log.warn(e.getMessage());
                }
            }
        }
        return pageDto;
    }

    private void fillPageDto(PageDto pageDto) throws IOException {
        String link = pageDto.getPath();
        log.info("Выполняется HTTP запрос к url = ".concat(link));
        Document doc = Jsoup.connect(link)
                .userAgent(USER_AGENT)
                .referrer(REFERER)
                .get();
        pageDto.setContent(doc.html());
        pageDto.setLinks(doc.select("a"));
    }
}
