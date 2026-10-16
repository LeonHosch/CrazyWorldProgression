package ekuzo.crazyworldprogression.mobforge.template;

import java.util.List;

public final class TemplateValidationException extends Exception {
	private final List<String> errors;
	public TemplateValidationException(List<String> errors) {
		super(String.join("; ", errors));
		this.errors = List.copyOf(errors);
	}
	public List<String> errors() { return errors; }
}
