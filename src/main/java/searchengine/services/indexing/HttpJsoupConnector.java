package searchengine.services.indexing;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import searchengine.dto.PageDto;

import java.io.IOException;

@Slf4j
public class HttpJsoupConnector {

    private static final int REQUEST_TIMEOUT = 500;  // (мс) таймаут перед запросами к ссылкам

    public PageDto getPageDtoFromLink(String link) {
        PageDto pageDto = new PageDto();
        pageDto.setPath(link);
        pageDto.setCode(200);
        synchronized (this) {
            try {
                fillPageDtoFromLink(pageDto);  //  все ссылки на странице
            } catch (HttpStatusException e) {
                log.warn(e.getMessage());
                pageDto.setCode(e.getStatusCode());
            } catch (IOException e) {
                log.warn(e.getMessage());
                pageDto.setCode(HttpStatus.NOT_FOUND.value());
            }
        }
        return pageDto;
    }

    private void fillPageDtoFromLink(PageDto pageDto) throws IOException {
        try {
            Thread.sleep(REQUEST_TIMEOUT);
        } catch (InterruptedException e) {
            log.warn(e.getMessage());
            return;
        }

        String link = pageDto.getPath();
        log.info("Выполняется HTTP запрос к url = " + link);
        Document doc = Jsoup.connect(link).get();
        pageDto.setContent(doc.html());
        pageDto.setLinks(doc.select("a"));
    }
}
