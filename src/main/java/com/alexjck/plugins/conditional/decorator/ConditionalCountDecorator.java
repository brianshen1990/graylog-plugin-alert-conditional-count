package com.alexjck.plugins.conditional.decorator;

import com.google.inject.assistedinject.Assisted;

import org.graylog2.decorators.Decorator;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.decorators.SearchResponseDecorator;
import org.graylog2.rest.resources.search.responses.SearchResponse;

import javax.inject.Inject;

public class ConditionalCountDecorator implements SearchResponseDecorator {

	@SuppressWarnings("unused")
	private Decorator decorator;

	@Inject
	public ConditionalCountDecorator(@Assisted Decorator decorator) {
		this.decorator = decorator;
	}

	@Override
	public SearchResponse apply(SearchResponse searchResponse) {
		return searchResponse;
	}

	public interface Factory extends SearchResponseDecorator.Factory {
		@Override
		ConditionalCountDecorator create(Decorator decorator);

		@Override
		ConditionalCountDecorator.Config getConfig();

		@Override
		ConditionalCountDecorator.Descriptor getDescriptor();
	}

	public static class Config implements SearchResponseDecorator.Config {

		@Override
		public ConfigurationRequest getRequestedConfiguration() {
			return new ConfigurationRequest();
		}
	}

	public static class Descriptor extends SearchResponseDecorator.Descriptor {
		public Descriptor() {
			super("消息 搜索条件-数目 告警条件",
                    "https://github.com/alcampos/graylog-plugin-alert-conditional-count",
                    "该告警在满足条件且超过临界情况下被触发.");
		}
	}
}