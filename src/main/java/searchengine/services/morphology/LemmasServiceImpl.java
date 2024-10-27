package searchengine.services.morphology;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.SiteDto;
import searchengine.model.Lemma;
import searchengine.repositories.LemmaRepository;
import searchengine.services.morphology.exceptions.WordNotFitToDictionaryException;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LemmasServiceImpl implements LemmasService {

    private static final String WORD_TYPE_REGEX = "\\W\\w&&[^а-яА-Я\\s]";
    private static final String[] particlesNames = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ"};

    private final LuceneMorphology luceneMorphology;
    private final LemmaRepository lemmaRepository;

    // спринг по типу аргумента, ищет в своем контексте объект типа и
    // сам подставляет в конструктор, на этапе создания объекта
    // объект создается в классе LemmaConfiguration.java
    public LemmasServiceImpl(LuceneMorphology luceneMorphology,
                             LemmaRepository lemmaRepository) {
        this.luceneMorphology = luceneMorphology;
        this.lemmaRepository = lemmaRepository;
    }

    private LemmasServiceImpl(){
        throw new RuntimeException("Disallow construct");
    }

    /**
     * Метод разделяет текст на слова, находит все леммы и считает их количество.
     *
     * @param text текст из которого будут выбираться леммы
     * @return ключ является леммой, а значение количеством найденных лемм
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> collectLemmas(String text) {
        String[] words = arrayContainsRussianWords(text);
        HashMap<String, Integer> lemmas = new HashMap<>();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            try {
                checkWord(word);
            } catch (WordNotFitToDictionaryException e) {
                log.warn(e.getLocalizedMessage());
                continue;
            }

            List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
            if (anyWordBaseBelongToParticle(wordBaseForms)) {
                continue;
            }
            List<String> normalForms = luceneMorphology.getNormalForms(word);
            if (normalForms.isEmpty()) {
                continue;
            }

            String normalWord = normalForms.get(0);

            if (lemmas.containsKey(normalWord)) {
                lemmas.put(normalWord, lemmas.get(normalWord) + 1);
            } else {
                lemmas.put(normalWord, 1);
            }
        }
        return lemmas;
    }

    /**
     * @param text текст из которого собираем все леммы
     * @return набор уникальных лемм найденных в тексте
     */
    @Override
    @Transactional(readOnly = true)
    public Set<String> getLemmaSet(String text) {
        String[] textArray = arrayContainsRussianWords(text);
        Set<String> lemmaSet = new HashSet<>();
        for (String word : textArray) {
            if (!word.isEmpty() && isCorrectWordForm(word)) {
                try {
                    checkWord(word);
                } catch (WordNotFitToDictionaryException e) {
                    log.warn(e.getLocalizedMessage());
                    continue;
                }

                List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
                if (anyWordBaseBelongToParticle(wordBaseForms)) {
                    continue;
                }
                lemmaSet.addAll(luceneMorphology.getNormalForms(word));
            }
        }
        return lemmaSet;
    }

    /**
     * @param pageLemmasCount key-value мапа текущей страницы: "лемма" - "кол-во на странице"
     * @param siteDto Dto с инфой сайта
     */
    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<Lemma> handleLemmas(Map<String, Integer> pageLemmasCount, SiteDto siteDto) {
        Set<String> foundPageLemmas = pageLemmasCount.keySet();  // все леммы, найденные на странице

        Set<Lemma> existingSiteLemmaEntities = lemmaRepository
                .findLemmasBySiteId(siteDto.getId());  // все записи лемм текущего сайта из БД

        Set<String> existingSiteLemmas = getStringLemmasSet(existingSiteLemmaEntities);  // в String set
        Set<String> newPageLemmas = getExclusion(foundPageLemmas, existingSiteLemmas);  // новые леммы

        Set<String> existingPageLemmas = getIntersection(
                foundPageLemmas, existingSiteLemmas);  // уже записанные в БД леммы
        Set<Lemma> lemmasToIncrement = existingSiteLemmaEntities.stream()
                .filter(lemma -> existingPageLemmas.contains(lemma.getLemma()))
                .collect(Collectors.toSet());  // в Lemma set

        List<Lemma> newLemmas = initAndSaveNewPageLemmas(newPageLemmas, siteDto);  // сохраняем в БД
        List<Lemma> existingLemmas = incrementAndSaveExistingPageLemmas(lemmasToIncrement, siteDto);  // сохраняем в БД

        ArrayList<Lemma> lemmasToIndex = new ArrayList<>();
        lemmasToIndex.addAll(newLemmas);  // список новых лемм, найденных на странице
        lemmasToIndex.addAll(existingLemmas);  // список лемм, частота которых увеличилась на 1
        return lemmasToIndex;
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void decrementLemmasFrequencyOrRemoveByIds(Set<Integer> previousLemmasIds) {
        if (previousLemmasIds.isEmpty()) return;

        Set<Integer> idsToDelete = new HashSet<>();
        Set<Lemma> lemmasToSave = new HashSet<>();
        List<Lemma> foundLemmas = lemmaRepository.findAllById(previousLemmasIds);
        for (Lemma lemma : foundLemmas) {
            int newValue = lemma.getFrequency() - 1;
            if (newValue < 1) {
                idsToDelete.add(lemma.getId());
                continue;
            }
            lemma.setFrequency(newValue);
            lemmasToSave.add(lemma);
        }
        lemmaRepository.saveAllAndFlush(lemmasToSave);
        lemmaRepository.deleteAllById(idsToDelete);
        lemmaRepository.flush();

        log.info("Обработали " + previousLemmasIds.size() + " лемм с предыдущей индексации сайта");
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Lemma> findAllByLemmaInOrderByFrequencyAsc(Set<String> queryLemmas) {
        return lemmaRepository.findAllByLemmaInOrderByFrequencyAsc(queryLemmas);  // поиск по леммам из поисковой строки
    }

    @Override
    @Transactional(readOnly = true)
    public int countAllBySiteId(int siteId) {
        return lemmaRepository.countAllBySiteId(siteId);
    }

    private String[] arrayContainsRussianWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-я\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private boolean anyWordBaseBelongToParticle(List<String> wordBaseForms) {
        return wordBaseForms.stream()
                .anyMatch(this::hasParticleProperty);
    }

    private boolean hasParticleProperty(String wordBase) {
        for (String property : particlesNames) {
            if (wordBase.toUpperCase().contains(property)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCorrectWordForm(String word) {
        List<String> wordInfo = luceneMorphology.getMorphInfo(word);
        for (String morphInfo : wordInfo) {
            if (morphInfo.matches(WORD_TYPE_REGEX)) {
                return false;
            }
        }
        return true;
    }

    private void checkWord(String word) {
        if (!luceneMorphology.checkString(word)) {
            // если слово не подходит для морфологического анализа - бросаем исключение
            // такое исключение можно перехватить внутри Spring и создать специальный ответ
            // смотри exceptions/DefaultAdvice.java
            throw new WordNotFitToDictionaryException(word);
        }
    }

    private Set<String> getStringLemmasSet(Set<Lemma> lemmasSet) {
        return lemmasSet.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
    }

    private Set<String> getIntersection(Set<String> s1, Set<String> s2) {
        Set<String> intersection = new HashSet<>(s1);
        intersection.retainAll(s2);
        return intersection;
    }

    private Set<String> getExclusion(Set<String> s1, Set<String> s2) {
        Set<String> exclusion = new HashSet<>(s1);
        exclusion.removeAll(s2);
        return exclusion;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private List<Lemma> initAndSaveNewPageLemmas(Set<String> newPageLemmas, SiteDto siteDto) {
        List<Lemma> lemmasToSave = new ArrayList<>();

        if (newPageLemmas.isEmpty()) {
            return lemmasToSave;
        }

        for (String lemmaValue : newPageLemmas) {
            Lemma lemma = new Lemma();
            lemma.setSiteId(siteDto.getId());
            lemma.setLemma(lemmaValue);
            lemma.setFrequency(1);
            lemmasToSave.add(lemma);
        }

        log.info("Сохранили " + newPageLemmas.size() + " новые леммы для сайта \"" +
                siteDto.getUrl() + "\": " +
                String.join(", ", newPageLemmas));
        return lemmaRepository.saveAllAndFlush(lemmasToSave);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private List<Lemma> incrementAndSaveExistingPageLemmas(
            Set<Lemma> lemmasEntitiesToIncrement, SiteDto siteDto) {
        if (lemmasEntitiesToIncrement.isEmpty()) return List.of();

        Set<String> lemmasToIncrement = lemmasEntitiesToIncrement.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
        for (Lemma lemma : lemmasEntitiesToIncrement) {
            lemma.setFrequency(lemma.getFrequency() + 1);
        }

        log.info("Увеличили frequency у " + lemmasToIncrement.size() + " существующих лемм сайта \"" +
                siteDto.getUrl() + "\": " +
                String.join(", ", lemmasToIncrement));
        return lemmaRepository.saveAllAndFlush(lemmasEntitiesToIncrement);
    }
}