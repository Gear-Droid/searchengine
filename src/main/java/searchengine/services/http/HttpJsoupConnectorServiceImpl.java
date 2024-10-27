package searchengine.services.http;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.PageDto;

import java.io.IOException;

@Slf4j
public class HttpJsoupConnectorServiceImpl implements HttpJsoupConnectorService {

    private static final String USER_AGENT = "SkillboxFinalTaskSearchBot";
    private static final String REFERER = "http://www.google.com";

    @Override
    @Transactional(readOnly = true)
    public PageDto getPageDtoFromLink(String link) throws InterruptedException {
        PageDto pageDto = new PageDto();
        pageDto.setPath(link);

        synchronized (this) {
            try {
                Thread.sleep(REQUEST_TIMEOUT);
            } catch (InterruptedException e) {
                log.warn("Сервис запросов к ссылкам прерван - " + e.getMessage());
                throw e;
            }

            try {
                fillPageDto(pageDto);
                pageDto.setCode(200);
            } catch (HttpStatusException e) {
                log.warn("[" + link + "] ошибка статуса страницы: " + e.getMessage());
                pageDto.setCode(e.getStatusCode());
            } catch (IOException e) {
                log.warn("[" + link + "] 404 Ошибка: " + e.getMessage());
                pageDto.setCode(HttpStatus.NOT_FOUND.value());
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
