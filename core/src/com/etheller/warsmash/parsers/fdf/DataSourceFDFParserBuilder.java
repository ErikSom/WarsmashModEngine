package com.etheller.warsmash.parsers.fdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import com.etheller.warsmash.datasources.DataSource;
import com.etheller.warsmash.fdfparser.FDFLexer;
import com.etheller.warsmash.fdfparser.FDFParser;
import com.etheller.warsmash.fdfparser.FDFParserBuilder;

public class DataSourceFDFParserBuilder implements FDFParserBuilder {
	private final DataSource dataSource;

	public DataSourceFDFParserBuilder(final DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public FDFParser build(final String path) {
		FDFLexer lexer;
		try (InputStream stream = this.dataSource.getResourceAsStream(path)) {
			lexer = new FDFLexer(CharStreams.fromString(readString(stream)));
		}
		catch (final IOException e) {
			throw new RuntimeException(e);
		}
		final FDFParser fdfParser = new FDFParser(new CommonTokenStream(lexer));
		final BaseErrorListener errorListener = new BaseErrorListener() {
			@Override
			public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol, final int line,
					final int charPositionInLine, final String msg, final RecognitionException e) {
				String sourceName = path;
				if (!sourceName.isEmpty()) {
					sourceName = String.format("%s:%d:%d: ", sourceName, line, charPositionInLine);
				}

				System.err.println(sourceName + "line " + line + ":" + charPositionInLine + " " + msg);
			}
		};
		fdfParser.addErrorListener(errorListener);
		return fdfParser;
	}

	private static String readString(final InputStream stream) throws IOException {
		final StringBuilder builder = new StringBuilder();
		final byte[] buffer = new byte[4096];
		int read;
		while ((read = stream.read(buffer)) != -1) {
			builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
		}
		return builder.toString();
	}
}
