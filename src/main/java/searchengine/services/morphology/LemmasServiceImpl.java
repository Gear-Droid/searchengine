package searchengine.services.morphology;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.SiteDto;
import searchengine.model.Lemma;
import searchengine.repositories.LemmaRepository;
import searchengine.services.morphology.exceptions.WordNotFitToDictionaryException;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LemmasServiceImpl implements LemmasService {

    static final String WORD_TYPE_REGEX = "\\W\\w&&[^а-яА-Я\\s]";
    static final String[] PARTICLES = new String[]{"МЕЖД", "ПРЕДЛ", "СОЮЗ"};
    static final String PUNCTUATION = "\\,\\.\\!\\?\\;\\:\\–\\-";

    private final LuceneMorphology luceneMorphology;
    private final LemmaRepository lemmaRepository;

    @Override
    public Map<String, Integer> collectLemmas(String text) {
        String[] ruWords = arrayContainsRussianWords(text);
        HashMap<String, Integer> lemmas = new HashMap<>();

        for (String word : ruWords) {
            if (word.isBlank() || isNotInDictionaryOrParticle(word)) continue;
            Optional<String> optionalNormWord = getFirstNormalForm(word);
            if (optionalNormWord.isEmpty()) continue;

            int lemmaCount = lemmas.getOrDefault(optionalNormWord.get(), 0);
            lemmas.put(optionalNormWord.get(), lemmaCount + 1);
        }
        return lemmas;
    }

    private Optional<String> getFirstNormalForm(String word) {
        List<String> normalForms = luceneMorphology.getNormalForms(word);
        if (normalForms.isEmpty()) return Optional.empty();
        return Optional.of(normalForms.get(0));
    }

    @Override
    public Set<String> getLemmaSet(String text) {
        String[] textArray = arrayContainsRussianWords(text);
        Set<String> lemmaSet = new HashSet<>();
        for (String word : textArray) {
            if (!word.isEmpty() && isCorrectWordForm(word) && !isNotInDictionaryOrParticle(word)) {
                lemmaSet.addAll(luceneMorphology.getNormalForms(word));
            }
        }
        return lemmaSet;
    }

    @Override
    @Transactional
    public List<Lemma> handleLemmas(SiteDto siteDto, Set<String> foundPageLemmas) {
        Set<Lemma> existingSiteLemmaEntities = lemmaRepository.findAllBySiteId(siteDto.getId());
        return initOrIncrementLemmasFrequency(siteDto, foundPageLemmas, existingSiteLemmaEntities);
    }

    private List<Lemma> initOrIncrementLemmasFrequency(SiteDto siteDto,
                                                Set<String> foundPageLemmas, Set<Lemma> existingSiteLemmaEntities) {
        ArrayList<Lemma> lemmasToIndex = new ArrayList<>();
        lemmasToIndex.addAll(
                incrementExistingLemmaEntities(siteDto, foundPageLemmas, existingSiteLemmaEntities));
        lemmasToIndex.addAll(
                createNewLemmaEntities(siteDto, foundPageLemmas, getStringLemmasSet(existingSiteLemmaEntities)));
        return lemmasToIndex;
    }

    private List<Lemma> incrementExistingLemmaEntities(SiteDto siteDto,
                                               Set<String> foundPageLemmas, Set<Lemma> existingSiteLemmaEntities) {
        Set<String> existingPageLemmas = getIntersection(foundPageLemmas, getStringLemmasSet(existingSiteLemmaEntities));
        Set<Lemma> lemmasToIncrement = existingSiteLemmaEntities.stream()
                .filter(lemma -> existingPageLemmas.contains(lemma.getLemma()))
                .collect(Collectors.toSet());
        return incrementAndSaveExistingPageLemmas(lemmasToIncrement, siteDto);
    }

    private List<Lemma> createNewLemmaEntities(SiteDto siteDto,
                                               Set<String> foundPageLemmas, Set<String> existingSiteLemmas) {
        Set<String> newPageLemmas = getExclusion(foundPageLemmas, existingSiteLemmas);
        return initAndSaveNewPageLemmas(newPageLemmas, siteDto);
    }

    private List<Lemma> incrementAndSaveExistingPageLemmas(Set<Lemma> lemmaEntitiesToIncrement, SiteDto siteDto) {
        if (lemmaEntitiesToIncrement.isEmpty()) return List.of();
        lemmaEntitiesToIncrement.forEach(lemma -> lemma.setFrequency(lemma.getFrequency() + 1));
        String lemmas = lemmaEntitiesToIncrement.stream()
                .peek(lemma -> lemma.setFrequency(lemma.getFrequency() + 1))
                .map(Lemma::getLemma)
                .collect(Collectors.joining(", "));
        log.info("Увеличили frequency " + lemmaEntitiesToIncrement.size() + " лемм сайта \"" +
                siteDto.getUrl() + "\": " + lemmas);
        return lemmaRepository.saveAllAndFlush(lemmaEntitiesToIncrement);
    }

    private List<Lemma> initAndSaveNewPageLemmas(Set<String> newPageLemmas, SiteDto siteDto) {
        if (newPageLemmas.isEmpty()) return List.of();
        List<Lemma> newLemmas = newPageLemmas.stream()
                .map(lemmaValue -> new Lemma(null, siteDto.getId(), lemmaValue, 1))
                .toList();
        log.info("Сохранили " + newLemmas.size() + " новые леммы для сайта \"" +
                siteDto.getUrl() + "\": " + String.join(", ", newPageLemmas));
        return lemmaRepository.saveAllAndFlush(newLemmas);
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

    @Override
    @Transactional
    public void decrementLemmasFrequencyOrRemoveByIds(Set<Integer> previousLemmasIds) {
        if (previousLemmasIds.isEmpty()) return;

        Set<Integer> idsToDelete = new HashSet<>();
        Set<Lemma> lemmasToSave = new HashSet<>();
        List<Lemma> foundLemmas = lemmaRepository.findAllById(previousLemmasIds);
        foundLemmas.forEach(lemma -> {
            int newValue = lemma.getFrequency() - 1;
            if (newValue < 1) idsToDelete.add(lemma.getId());
            else {
                lemma.setFrequency(newValue);
                lemmasToSave.add(lemma);
            }
        });
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

    public String getSnippetFromContentByLemmaValues(String content, Set<String> lemmasToFind) {
        return getSnippet(arrayContainsRussianWordsAndPunctuation(content), lemmasToFind);
    }

    private String getSnippet(String[] ruWordsWithPunctuation, Set<String> lemmasToFind) {
        StringJoiner sj = new StringJoiner(" ... ");
        int sliceIntervalLength = 5;
        for (int i = 0; i < ruWordsWithPunctuation.length; i++) {
            String word = ruWordsWithPunctuation[i].replaceAll("[" + PUNCTUATION + "]", "");
            if (word.isBlank()) continue;

            Optional<String> optionalNormWord = getFirstNormalForm(word.toLowerCase());
            if (optionalNormWord.isEmpty() || !lemmasToFind.contains(optionalNormWord.get().toLowerCase())) continue;

            ruWordsWithPunctuation[i] = "<b>%s</b>".formatted(ruWordsWithPunctuation[i]);
            String[] slice = Arrays.copyOfRange(ruWordsWithPunctuation,
                    Math.max(i - sliceIntervalLength, 0),
                    Math.min(i + sliceIntervalLength + 1, ruWordsWithPunctuation.length));
            sj.add(String.join(" ", slice));
            i += sliceIntervalLength;
            if (sj.length() > 150) break;
        }
        return sj.toString().concat(" ...");
    }

    private String[] arrayContainsRussianWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("([^а-яА-Я\\s])", " ")
                .trim()
                .split("\\s+");
    }

    private String[] arrayContainsRussianWordsAndPunctuation(String text) {
        return text.replaceAll("[^а-яА-Я\\s" + PUNCTUATION + "]", "")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("[\\s" + PUNCTUATION + "]{3,}", ". ")
                .split("\\s+");
    }

    private boolean anyWordBaseBelongToParticle(List<String> wordBaseForms) {
        return wordBaseForms.stream()
                .anyMatch(this::hasParticleProperty);
    }

    private boolean hasParticleProperty(String wordBase) {
        for (String property : PARTICLES) {
            if (wordBase.toUpperCase().contains(property)) return true;
        }
        return false;
    }

    private boolean isCorrectWordForm(String word) {
        List<String> wordInfo = luceneMorphology.getMorphInfo(word);
        for (String morphInfo : wordInfo) {
            if (morphInfo.matches(WORD_TYPE_REGEX)) return false;
        }
        return true;
    }

    private boolean isNotInDictionaryOrParticle(String word) {
        try {
            if (!luceneMorphology.checkString(word)) throw new WordNotFitToDictionaryException(word);
            List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
            return anyWordBaseBelongToParticle(wordBaseForms);
        } catch (WordNotFitToDictionaryException e) {
            log.warn(e.getLocalizedMessage());
            return true;
        }
    }

    private Set<String> getStringLemmasSet(Set<Lemma> lemmasSet) {
        return lemmasSet.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
    }

}