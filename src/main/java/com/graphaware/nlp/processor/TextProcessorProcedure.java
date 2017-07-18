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
package com.graphaware.nlp.processor;

import com.graphaware.nlp.domain.AnnotatedText;
import com.graphaware.nlp.domain.Labels;
import com.graphaware.nlp.domain.Properties;
import com.graphaware.nlp.language.LanguageManager;
import com.graphaware.nlp.procedure.NLPProcedure;
import com.graphaware.nlp.util.GenericModelParameters;
import com.graphaware.nlp.util.OptionalNLPParameters;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.MultipleFoundException;
import org.neo4j.graphdb.Node;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.Neo4jTypes;
import org.neo4j.kernel.api.proc.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.neo4j.procedure.Mode;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureSignature;

public class TextProcessorProcedure extends NLPProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(TextProcessorProcedure.class);
    public static final String SUCCESS = "success";

    private String defaultTextProcessorName;
    private final TextProcessor textProcessor;
    private final GraphDatabaseService database;
    private final TextProcessorsManager processorManager;

    private static final String PARAMETER_NAME_TEXT = "text";
    private static final String PARAMETER_NAME_FILTER = "filter";
    private static final String PARAMETER_NAME_ANNOTATED_TEXT = "node";
    private static final String PARAMETER_NAME_ID = "id";
    private static final String PARAMETER_NAME_DEEP_LEVEL = "nlpDepth";
    private static final String PARAMETER_NAME_STORE_TEXT = "store";
    private static final String PARAMETER_NAME_LANGUAGE_CHECK = "languageCheck";
    private static final String PARAMETER_NAME_OUTPUT_TP_CLASS = "class";

    private static final String PARAMETER_NAME_TRAIN_PROJECT = "project";
    private static final String PARAMETER_NAME_TRAIN_ALG = "alg";
    private static final String PARAMETER_NAME_TRAIN_MODEL = "model";
    private static final String PARAMETER_NAME_TRAIN_FILE = "file";
    private static final String PARAMETER_NAME_TRAIN_LANG = "lang";
    private static final List<String> TRAINING_PARAMETERS = Arrays.asList(GenericModelParameters.TRAIN_ALG, GenericModelParameters.TRAIN_TYPE, GenericModelParameters.TRAIN_ITER, GenericModelParameters.TRAIN_CUTOFF,
            GenericModelParameters.TRAIN_THREADS, GenericModelParameters.TRAIN_ENTITYTYPE, GenericModelParameters.VALIDATE_FOLDS, GenericModelParameters.VALIDATE_FILE);

    private static final String PARAMETER_NAME_FORCE = "force";

    public TextProcessorProcedure(GraphDatabaseService database, TextProcessorsManager processorManager) {
        this.database = database;
        this.processorManager = processorManager;

        this.defaultTextProcessorName = processorManager.getDefaultProcessorName();
        this.textProcessor = processorManager.getDefaultProcessor();
        if (this.textProcessor == null) {
            LOG.warn("Extraction of the default text processor (" + this.defaultTextProcessorName + ") failed.");
        }
    }

    public CallableProcedure.BasicProcedure annotate() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("annotate"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {

                try {
                    checkIsMap(input[0]);
                    Map<String, Object> inputParams = (Map) input[0];
                    String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                    if (text == null) {
                        LOG.info("Text is null.");
                        return Iterators.asRawIterator(Collections.<Object[]>emptyIterator());
                    }
                    boolean checkForLanguage = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_LANGUAGE_CHECK, true);
                    LOG.info("Annotating Text: " + text);
                    String lang = LanguageManager.getInstance().detectLanguage(text);
                    if (checkForLanguage && !LanguageManager.getInstance().isTextLanguageSupported(text)) {
                        LOG.info("Language not supported or unable to detect the language. Detected language: " + lang);
                        return Iterators.asRawIterator(Collections.<Object[]>emptyIterator());
                    }
                    Object id = inputParams.get(PARAMETER_NAME_ID);
                    if (id == null) {
                        LOG.error("Node ID with key " + PARAMETER_NAME_ID + " is null!");
                    }
                    Node annotatedText = checkIfExist(id);
                    boolean store = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_STORE_TEXT, true);
                    boolean force = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_FORCE, false);

                    // optional parameters
                    Map<String, String> otherPars = extractOptionalParameters(inputParams);

                    if (annotatedText == null || force) {
                        AnnotatedText annotateText;
                        String pipeline = (String) inputParams.getOrDefault(PARAMETER_NAME_TEXT_PIPELINE, "");
                        TextProcessor currentTP = retrieveTextProcessor(inputParams, pipeline);
                        annotateText = currentTP.annotateText(text, id, pipeline, lang, store, otherPars);
                        annotatedText = annotateText.storeOnGraph(database, force);
                    }
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedText}).iterator());
                } catch (Exception ex) {
                    LOG.error("Error while annotating", ex);
                    throw ex;
                }
            }

            private Node checkIfExist(Object id) {
                if (id != null) {
                    try {
                        return database.findNode(Labels.AnnotatedText, Properties.PROPERTY_ID, id);
                    } catch (MultipleFoundException e) {
                        LOG.warn("Multiple AnnotatedText nodes found for id " + id);
                        throw new RuntimeException(e);
                    }
                }
                return null;
            }
        };
    }

    public CallableProcedure.BasicProcedure language() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("language"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                String language = LanguageManager.getInstance().detectLanguage(text);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{language}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure filter() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("filter"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTBoolean).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                String lang = LanguageManager.getInstance().detectLanguage(text);
                if (text == null || !LanguageManager.getInstance().isTextLanguageSupported(text)) {
                    LOG.info("text is null or language not supported or unable to detect the language");
                    return Iterators.asRawIterator(Collections.<Object[]>emptyIterator());
                }
                String filter = (String) inputParams.get(PARAMETER_NAME_FILTER);
                if (filter == null) {
                    throw new RuntimeException("A filter value needs to be provided");
                }
                TextProcessor currentTP = retrieveTextProcessor(inputParams, "");
                AnnotatedText annotatedText = currentTP.annotateText(text, 0, 0, lang, false);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedText.filter(filter)}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure sentiment() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("sentiment"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                Node annotatedNode = (Node) inputParams.get(PARAMETER_NAME_ANNOTATED_TEXT);

                // extract optional parameters
                Map<String, String> otherParams = extractOptionalParameters(inputParams);

                AnnotatedText annotatedText = AnnotatedText.load(annotatedNode);
                TextProcessor currentTP = retrieveTextProcessor(inputParams, "");
                annotatedText = currentTP.sentiment(annotatedText, otherParams);
                annotatedText.storeOnGraph(database, false);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedNode}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure getProcessors() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("getProcessors"))
                .mode(Mode.WRITE)
                .out(PARAMETER_NAME_OUTPUT_TP_CLASS, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                Set<String> textProcessors = processorManager.getTextProcessors();
                Set<Object[]> result = new HashSet<>();
                textProcessors.forEach(row -> {
                    result.add(new Object[]{row});
                });
                return Iterators.asRawIterator(result.iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure getPipelines() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("getPipelines"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String textProcessor = (String) inputParams.get(PARAMETER_NAME_TEXT_PROCESSOR);
                TextProcessor textProcessorInstance = processorManager.getTextProcessor(textProcessor);
                Set<Object[]> result = new HashSet<>();
                List<String> pipelines = textProcessorInstance.getPipelines();
                pipelines.forEach(row -> {
                    result.add(new Object[]{row});
                });
                return Iterators.asRawIterator(result.iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure addPipeline() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("addPipeline"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                TextProcessorsManager.PipelineCreationResult creationResult = processorManager.createPipeline(inputParams);
                //if succeeded
                processorManager.storePipelines(inputParams);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{
                    creationResult.getResult() == 0 ? SUCCESS : "Error: " + creationResult.getMessage()
                }).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure removePipeline() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("removePipeline"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];

                String processor = ((String) inputParams.getOrDefault(PARAMETER_NAME_TEXT_PROCESSOR, ""));
                if (processor.length() > 0) {
                    String pipeline = ((String) inputParams.getOrDefault(PARAMETER_NAME_TEXT_PIPELINE, ""));
                    if (pipeline.length() == 0) {
                        throw new RuntimeException("You need to specify a pipeline");
                    }
                    processorManager.removePipeline(processor, pipeline);
                }
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{SUCCESS}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure train() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("train"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];

                // mandatory arguments
                if (!inputParams.containsKey(PARAMETER_NAME_TRAIN_ALG)
                        || !inputParams.containsKey(PARAMETER_NAME_TRAIN_MODEL)
                        || !inputParams.containsKey(PARAMETER_NAME_TRAIN_FILE)) {
                    throw new RuntimeException("You need to specify mandatory parameters: " + PARAMETER_NAME_TRAIN_ALG + ", " + PARAMETER_NAME_TRAIN_MODEL + ", " + PARAMETER_NAME_TRAIN_FILE);
                }
                String alg = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_ALG));
                String model = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_MODEL));
                String file = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_FILE));

                // optional arguments
                String project = String.valueOf(inputParams.getOrDefault(PARAMETER_NAME_TRAIN_PROJECT, "default"));
                String lang = String.valueOf(inputParams.getOrDefault(PARAMETER_NAME_TRAIN_LANG, "en"));

                // training parameters (optional)
                Map<String, String> params = new HashMap<String, String>();
                TRAINING_PARAMETERS.forEach(par -> {
                    if (inputParams.containsKey(par)) {
                        params.put(par, String.valueOf(inputParams.get(par)));
                    }
                });

                // check training parameters consistency: are there some unexpected keys? (possible typos)
                List<String> unusedKeys = new ArrayList<String>();
                List<String> otherKeys = Arrays.asList(PARAMETER_NAME_TRAIN_ALG, PARAMETER_NAME_TRAIN_MODEL, PARAMETER_NAME_TRAIN_FILE, PARAMETER_NAME_TRAIN_PROJECT, PARAMETER_NAME_TRAIN_LANG);
                inputParams.forEach((k, v) -> {
                    if (!TRAINING_PARAMETERS.contains(k) && !otherKeys.contains(k)) {
                        unusedKeys.add(k);
                    }
                });
                if (unusedKeys.size() > 0) {
                    LOG.warn("Warning! Unused parameter(s) (possible typos?): " + String.join(", ", unusedKeys));
                }

                TextProcessor currentTP = retrieveTextProcessor(inputParams, "");

                String res = currentTP.train(project, alg, model, file, lang, params);

                if (res.length() > 0) {
                    res = "success: " + res;
                } else {
                    res = "failure";
                }

                if (unusedKeys.size() > 0) {
                    res += "; Warning, unsed parameter(s): " + String.join(", ", unusedKeys);
                }

                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{res}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure test() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("test"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTString)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];

                // mandatory arguments
                if (!inputParams.containsKey(PARAMETER_NAME_TRAIN_ALG)
                        || !inputParams.containsKey(PARAMETER_NAME_TRAIN_MODEL)
                        || !inputParams.containsKey(PARAMETER_NAME_TRAIN_FILE)) {
                    throw new RuntimeException("You need to specify mandatory parameters: " + PARAMETER_NAME_TRAIN_ALG + ", " + PARAMETER_NAME_TRAIN_MODEL + ", " + PARAMETER_NAME_TRAIN_FILE);
                }
                String alg = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_ALG));
                String model = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_MODEL));
                String file = String.valueOf(inputParams.get(PARAMETER_NAME_TRAIN_FILE));

                // optional arguments
                String project = String.valueOf(inputParams.getOrDefault(PARAMETER_NAME_TRAIN_PROJECT, "default"));
                String lang = String.valueOf(inputParams.getOrDefault(PARAMETER_NAME_TRAIN_LANG, "en"));

                TextProcessor currentTP = retrieveTextProcessor(inputParams, "");

                String res = currentTP.test(project, alg, model, file, lang);

                if (res.length() > 0) {
                    res = "success: " + res;
                } else {
                    res = "failure";
                }

                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{res}).iterator());
            }
        };
    }

    private Map<String, String> extractOptionalParameters(Map<String, Object> input) {
        Map<String, String> otherPars = new HashMap<String, String>();
        if (input.containsKey(OptionalNLPParameters.CUSTOM_PROJECT)) {
            otherPars.put(OptionalNLPParameters.CUSTOM_PROJECT, String.valueOf(input.get(OptionalNLPParameters.CUSTOM_PROJECT)));
        }
        if (input.containsKey(OptionalNLPParameters.SENTIMENT_PROB_THR)) {
            otherPars.put(OptionalNLPParameters.SENTIMENT_PROB_THR, String.valueOf(input.get(OptionalNLPParameters.SENTIMENT_PROB_THR)));
        }
        return otherPars;
    }

    private TextProcessor retrieveTextProcessor(Map<String, Object> inputParams, String pipeline) {
        TextProcessor newTP = this.textProcessor; // default processor
        String newTPName = this.defaultTextProcessorName;
        String processor = (String) inputParams.getOrDefault(PARAMETER_NAME_TEXT_PROCESSOR, "");
        if (processor.length() > 0) {
            newTPName = processor;
            newTP = processorManager.getTextProcessor(processor);
            if (newTP == null) {
                throw new RuntimeException("Text processor " + processor + " doesn't exist");
            }
        }
        if (pipeline.length() > 0) {
            if (!newTP.checkPipeline(pipeline)) {
                throw new RuntimeException("Pipeline with name " + pipeline + " doesn't exist for processor " + processor);
            }
        }
        LOG.info("Using text processor: " + newTPName);

        return newTP;
    }
}