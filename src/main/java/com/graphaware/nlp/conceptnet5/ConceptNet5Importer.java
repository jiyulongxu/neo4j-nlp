/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.nlp.conceptnet5;

import com.graphaware.nlp.domain.Tag;
import com.graphaware.nlp.processor.TextProcessor;
import java.util.ArrayList;
import java.util.List;

public class ConceptNet5Importer {
    
    private static final String[] DEFAULT_ADMITTED_RELATIONSHIP = {"RelatedTo", "IsA", "PartOf", "AtLocation", "Synonym", "MemberOf", "HasA", "CausesDesire"};


    private final ConceptNet5Client client;
    private final TextProcessor nlpProcessor;
    private String[] admittedRelations;
    private int depthSearch = 2;

    public ConceptNet5Importer(String conceptNet5EndPoint, TextProcessor nlpProcessor, int depth, String... admittedRelations) {
        this(new ConceptNet5Client(conceptNet5EndPoint), nlpProcessor, depth, admittedRelations);
    }

    public ConceptNet5Importer(ConceptNet5Client client, TextProcessor nlpProcessor, int depth, String... admittedRelations) {
        this.client = client;
        this.nlpProcessor = nlpProcessor;
        this.admittedRelations = admittedRelations;
        this.depthSearch = depth;
    }

    private ConceptNet5Importer(Builder builder) {
        this.client = builder.client;
        this.nlpProcessor = builder.nlpProcessor;
        this.admittedRelations = builder.admittedRelations;
        this.depthSearch = builder.depthSearch;
    }

    public List<Tag> importHierarchy(Tag source, String lang) {
        return importConceptHierarchy(source, lang, depthSearch, DEFAULT_ADMITTED_RELATIONSHIP);
    }

    private List<Tag> importConceptHierarchy(Tag source, String lang, int depth, String... admittedRelations) {
        String word = source.getLemma().toLowerCase();
        ConceptNet5EdgeResult values = client.getValues(word, lang);
        List<Tag> res = new ArrayList<>();
        values.getEdges().stream().forEach((concept) -> {
            String conceptPrefix = "/c/" + lang + "/";
            String parentConcept = concept.getEnd().substring(conceptPrefix.length());
            if (concept.getStart().equalsIgnoreCase(conceptPrefix + word)
                    && checkAdmittedRelations(concept, admittedRelations)) {
                Tag annotateTag = tryToAnnotate(parentConcept);
                if (depth > 1) {
                    importConceptHierarchy(annotateTag, lang, depth - 1, admittedRelations);
                }
                source.addParent(concept.getRel(), annotateTag);
                res.add(annotateTag);
            }
        });
        return res;
    }

    private Tag tryToAnnotate(String parentConcept) {
        Tag annotateTag = nlpProcessor.annotateTag(parentConcept);
        if (annotateTag == null) {
            annotateTag = new Tag(parentConcept);
        }
        return annotateTag;
    }

    private boolean checkAdmittedRelations(ConceptNet5Concept concept, String... admittedRelations) {
        if (admittedRelations == null) {
            return true;
        }
        for (String rel : admittedRelations) {
            if (concept.getRel().contains(rel)) {
                return true;
            }
        }
        return false;
    }

    public static class Builder {

        private final ConceptNet5Client client;
        private final TextProcessor nlpProcessor;
        private String[] admittedRelations = DEFAULT_ADMITTED_RELATIONSHIP;
        private int depthSearch = 2;

        public Builder(String cnet5Host, TextProcessor nlpProcessor) {
            this(new ConceptNet5Client(cnet5Host), nlpProcessor);            
        }
        public Builder(ConceptNet5Client client, TextProcessor nlpProcessor) {
            this.client = client;
            this.nlpProcessor = nlpProcessor;
        }

        public Builder setAdmittedRelations(String[] admittedRelations) {
            this.admittedRelations = admittedRelations;
            return this;
        }

        public Builder setDepthSearch(int depthSearch) {
            this.depthSearch = depthSearch;
            return this;
        }
        
        public ConceptNet5Importer build() {
            return new ConceptNet5Importer(this);
        }
    }
}
