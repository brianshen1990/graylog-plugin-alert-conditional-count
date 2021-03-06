package com.alexjck.plugins.conditional.alerts;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.graylog2.Configuration;
import org.graylog2.alerts.AbstractAlertCondition;
import org.graylog2.indexer.results.CountResult;
import org.graylog2.indexer.results.ResultMessage;
import org.graylog2.indexer.results.SearchResult;
import org.graylog2.indexer.searches.Searches;
import org.graylog2.indexer.searches.Sorting;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.Tools;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.indexer.searches.timeranges.AbsoluteRange;
import org.graylog2.plugin.indexer.searches.timeranges.InvalidRangeParametersException;
import org.graylog2.plugin.indexer.searches.timeranges.RelativeRange;
import org.graylog2.plugin.streams.Stream;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

public class ConditionalCountAlertCondition extends AbstractAlertCondition {
    private static final Logger LOG = LoggerFactory.getLogger(ConditionalCountAlertCondition.class);

    enum ThresholdType {

        MORE("多于"),
        LESS("少于");

        private final String description;

        ThresholdType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
    
    private final Searches searches;
    @SuppressWarnings("unused")
	private final Configuration configuration;
    private final String query;
    private final int time;
    private final ThresholdType thresholdType;
    private final int threshold;

    public interface Factory extends AlertCondition.Factory {
        @Override
        ConditionalCountAlertCondition create(Stream stream,
                                               @Assisted("id") String id,
                                               DateTime createdAt,
                                               @Assisted("userid") String creatorUserId,
                                               Map<String, Object> parameters,
                                               @Assisted("title") @Nullable String title);

        @Override
        Config config();

        @Override
        Descriptor descriptor();
    }

    public static class Config implements AlertCondition.Config {
        public Config() {
        }

        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest configurationRequest = ConfigurationRequest.createWithFields(
                    new TextField("query", "查找条件", "", "查找条件", ConfigurationField.Optional.NOT_OPTIONAL),
                    new NumberField("time", "时间区域", 5, "以分钟为单位", ConfigurationField.Optional.NOT_OPTIONAL),
                    new DropdownField(
                            "threshold_type",
                            "临界类型",
                            ThresholdType.MORE.toString(),
                            Arrays.stream(ThresholdType.values()).collect(Collectors.toMap(Enum::toString, ThresholdType::getDescription)),
                            "选择触发告警的条件: 当存在多于或者少于临界值的消息被检测到",
                            ConfigurationField.Optional.NOT_OPTIONAL),
                    new NumberField("threshold", "临界值", 0.0, "告警触发的临界值", ConfigurationField.Optional.NOT_OPTIONAL)
            );
            configurationRequest.addFields(AbstractAlertCondition.getDefaultConfigurationFields());
            return configurationRequest;
        }
    }

    public static class Descriptor extends AlertCondition.Descriptor {
        public Descriptor() {
            super(
                "消息 搜索条件-数目 告警条件",
                "https://github.com/alcampos/graylog-plugin-alert-conditional-count",
                "该告警在满足条件且超过临界情况下被触发."
            );
        }
    }

	@AssistedInject
    public ConditionalCountAlertCondition(Searches searches,
                                           Configuration configuration,
                                           @Assisted Stream stream,
                                           @Nullable @Assisted("id") String id,
                                           @Assisted DateTime createdAt,
                                           @Assisted("userid") String creatorUserId,
                                           @Assisted Map<String, Object> parameters,
                                           @Assisted("title") @Nullable String title) {
        super(stream, id, ConditionalCountAlertCondition.class.getCanonicalName(), createdAt, creatorUserId, parameters, title);
        this.searches = searches;
        this.configuration = configuration;
        this.query = (String) parameters.get("query");
        this.time = Tools.getNumber(parameters.get("time"), 5).intValue();

        final String thresholdType = (String) parameters.get("threshold_type");
        final String upperCaseThresholdType = thresholdType.toUpperCase(Locale.ENGLISH);

        if (!thresholdType.equals(upperCaseThresholdType)) {
            final HashMap<String, Object> updatedParameters = new HashMap<>();
            updatedParameters.putAll(parameters);
            updatedParameters.put("threshold_type", upperCaseThresholdType);
            super.setParameters(updatedParameters);
        }
        this.thresholdType = ThresholdType.valueOf(upperCaseThresholdType);
        this.threshold = Tools.getNumber(parameters.get("threshold"), 0).intValue();
    }

    @Override
    public CheckResult runCheck() {       
		try {
			final RelativeRange relativeRange = RelativeRange.create(time * 60);
	        final AbsoluteRange range = AbsoluteRange.create(relativeRange.getFrom(), relativeRange.getTo());
	        final String filter = "streams:" + stream.getId();
	        final CountResult result = searches.count(query, range, filter);
	        final long count = result.count();
	        
            final boolean triggered;
            switch (thresholdType) {
                case MORE:
                    triggered = count > threshold;
                    break;
                case LESS:
                    triggered = count < threshold;
                    break;
                default:
                    triggered = false;
            }
            
            if (triggered) {
                final List<MessageSummary> summaries = Lists.newArrayList();
                if (getBacklog() > 0) {
                    final SearchResult backlogResult = searches.search("*", filter,
                        range, getBacklog(), 0, new Sorting(Message.FIELD_TIMESTAMP, Sorting.Direction.DESC));
                    for (ResultMessage resultMessage : backlogResult.getResults()) {
                        final Message msg = resultMessage.getMessage();
                        summaries.add(new MessageSummary(resultMessage.getIndex(), msg));
                    }
                }
                final String resultDescription = "数据流检测到 " + count + " 条消息, 在最近的 " + time
                        + " 分钟内， 切都满足条件 " + thresholdType.getDescription() +
                        +  threshold + ". (当前宽限期: " + grace + " 分钟)";
                return new CheckResult(true, this, resultDescription, Tools.nowUTC(), summaries);
            } else {
			    LOG.debug("Alert check <{}> returned no results.", id);
			    return new NegativeCheckResult();
            }
		} catch (InvalidRangeParametersException e) {
            LOG.error("Invalid timerange.", e);
            return null;
		}
    }

    @Override
    public String getDescription() {
        return "时间: " + time
                + ", 临界类型: " + thresholdType.getDescription()
                + ", 临界值: " + threshold
                + ", 宽限期: " + grace
                + ", 查找条件: " + query
                + ", 重复通知: " + ((repeatNotifications)?"是":"否");
    }

    @Override
    public String toString() {
        return id + ": 消息 搜索条件-数目 告警条件 ={" + getDescription() + "}" + ", 数据流:={" + stream + "}";
    }
}