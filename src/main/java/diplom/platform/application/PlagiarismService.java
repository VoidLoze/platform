package diplom.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import diplom.platform.evaluation.infrastructure.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlagiarismService {
    private final LabWorkJpaRepository labWorks;
    private final PlagiarismReportJpaRepository reports;
    private final ObjectMapper objectMapper;

    public PlagiarismService(LabWorkJpaRepository labWorks, PlagiarismReportJpaRepository reports, ObjectMapper objectMapper) {
        this.labWorks = labWorks;
        this.reports = reports;
        this.objectMapper = objectMapper;
    }

    public PlagiarismReportEntity buildReport(LabWorkEntity current) {
        List<LabWorkEntity> all = labWorks.findAll();
        double maxSimilarity = 0.0;
        for (LabWorkEntity other : all) {
            if (other.getId().equals(current.getId()) || other.getTextContent() == null) {
                continue;
            }
            maxSimilarity = Math.max(maxSimilarity, jaccardSimilarity(current.getTextContent(), other.getTextContent()));
        }
        PlagiarismReportEntity report = reports.findByLabWorkId(current.getId()).orElse(new PlagiarismReportEntity());
        report.setLabWork(current);
        report.setSimilarityScore(maxSimilarity);
        try {
            report.setMatchedSourcesJson(objectMapper.writeValueAsString(List.of("internal-corpus")));
        } catch (JsonProcessingException e) {
            report.setMatchedSourcesJson("[\"internal-corpus\"]");
        }
        return reports.save(report);
    }

    private double jaccardSimilarity(String a, String b) {
        java.util.Set<String> left = new java.util.HashSet<>(List.of(a.toLowerCase().split("\\W+")));
        java.util.Set<String> right = new java.util.HashSet<>(List.of(b.toLowerCase().split("\\W+")));
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(left);
        intersection.retainAll(right);
        java.util.Set<String> union = new java.util.HashSet<>(left);
        union.addAll(right);
        return ((double) intersection.size()) / union.size();
    }
}
