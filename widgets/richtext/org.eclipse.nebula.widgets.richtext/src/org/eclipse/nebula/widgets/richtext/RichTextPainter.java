/*****************************************************************************
 * Copyright (c) 2015, 2023 CEA LIST.
 *
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *		Dirk Fauth <dirk.fauth@googlemail.com> - Initial API and implementation
 *****************************************************************************/
package org.eclipse.nebula.widgets.richtext;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EntityReference;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.eclipse.nebula.widgets.richtext.painter.AlignmentStyle;
import org.eclipse.nebula.widgets.richtext.painter.DefaultEntityReplacer;
import org.eclipse.nebula.widgets.richtext.painter.EntityReplacer;
import org.eclipse.nebula.widgets.richtext.painter.LinePainter;
import org.eclipse.nebula.widgets.richtext.painter.ResourceHelper;
import org.eclipse.nebula.widgets.richtext.painter.SpanElement;
import org.eclipse.nebula.widgets.richtext.painter.SpanElement.SpanType;
import org.eclipse.nebula.widgets.richtext.painter.TagProcessingState;
import org.eclipse.nebula.widgets.richtext.painter.TagProcessingState.TextAlignment;
import org.eclipse.nebula.widgets.richtext.painter.instructions.BoldPaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.FontMetricsProvider;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ItalicPaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ListInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.NewLineInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.PaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ParagraphInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ResetFontPaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ResetParagraphInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.ResetSpanStylePaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.SpanStylePaintInstruction;
import org.eclipse.nebula.widgets.richtext.painter.instructions.TextPaintInstruction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

// TODO 0.2 - Painter: cutting if space is not enough
// TODO 0.2 - Painter: add scrolling support
// TODO 0.2 - Painter: improvements like caching of information

// TODO 0.2 - Extension: add ability to handle custom tags
// TODO 0.2 - Extension: add ability to specify interactions, e.g. links
// TODO 0.2 - Extension: add ability to include images

/**
 * The {@link RichTextPainter} is used to parse and render HTML input to a {@link GC}. It works well
 * with HTML input generated by ckeditor.
 */
public class RichTextPainter {

	public static final String TAG_SPAN = "span";
	public static final String TAG_STRONG = "strong";
	public static final String TAG_EM = "em";
	public static final String TAG_UNDERLINE = "u";
	public static final String TAG_STRIKETHROUGH = "s";
	public static final String TAG_PARAGRAPH = "p";
	public static final String TAG_UNORDERED_LIST = "ul";
	public static final String TAG_ORDERED_LIST = "ol";
	public static final String TAG_LIST_ITEM = "li";

	public static final String TAG_BR = "br";

	public static final String ATTRIBUTE_STYLE = "style";
	public static final String ATTRIBUTE_STYLE_COLOR = "color";
	public static final String ATTRIBUTE_STYLE_BACKGROUND_COLOR = "background-color";
	public static final String ATTRIBUTE_STYLE_FONT_SIZE = "font-size";
	public static final String ATTRIBUTE_STYLE_FONT_FAMILY = "font-family";

	public static final String ATTRIBUTE_PARAGRAPH_MARGIN_LEFT = "margin-left";
	public static final String ATTRIBUTE_PARAGRAPH_TEXT_ALIGN = "text-align";

	public static final String ATTRIBUTE_PARAGRAPH_TEXT_ALIGN_VALUE_RIGHT = "right";

	public static final String CONTROL_CHARACTER_REGEX = "\\n\\r|\\r\\n|\\n|\\r|\\t"; //$NON-NLS-1$

	public static final String FAKE_ROOT_TAG_START = "<root>";
	public static final String FAKE_ROOT_TAG_END = "</root>";

	public static final String[] BULLETS = new String[] { "\u2022", " \u25e6", "\u25aa" };
	public static final String SPACE = "\u00a0";

	private int paragraphSpace = 5;

	private boolean wordWrap;

	private final Point preferredSize = new Point(0, 0);

	XMLInputFactory factory = XMLInputFactory.newInstance();
	{
		// as we don't have a well-formed XML document, we need to take care of
		// entity references ourself
		factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
	}

	private EntityReplacer entityReplacer = new DefaultEntityReplacer();

	private String wordSplitRegex = "\\s";

	/**
	 * Create a new {@link RichTextPainter} with disabled word wrapping.
	 */
	public RichTextPainter() {
		this(false);
	}

	/**
	 * Create a new {@link RichTextPainter}.
	 *
	 * @param wordWrap
	 *            <code>true</code> if automatic word wrapping should be enabled, <code>false</code>
	 *            if not.
	 */
	public RichTextPainter(boolean wordWrap) {
		this.wordWrap = wordWrap;
	}

	/**
	 * Processes the HTML input to calculate the preferred size. Does not perform rendering.
	 *
	 * @param html
	 *            The HTML string to process.
	 * @param gc
	 *            The {@link GC} to operate on.
	 * @param bounds
	 *            The available space for painting.
	 * @param calculateWithWrapping
	 *            <code>true</code> if calculation should be performed with enabled word wrapping,
	 *            <code>false</code> if not
	 */
	public void preCalculate(String html, GC gc, Rectangle bounds, boolean calculateWithWrapping) {
		boolean original = this.wordWrap;
		this.wordWrap = calculateWithWrapping;
		paintHTML(html, gc, bounds, false);
		this.wordWrap = original;
	}

	/**
	 * Processes the HTML input and paints the result to the given {@link GC}.
	 *
	 * @param html
	 *            The HTML string to process.
	 * @param gc
	 *            The {@link GC} to operate on.
	 * @param bounds
	 *            The available space for painting.
	 */
	public void paintHTML(String html, GC gc, Rectangle bounds) {
		paintHTML(html, gc, bounds, true);
	}

	/**
	 * Processes the HTML input.
	 *
	 * @param html
	 *            The HTML string to process.
	 * @param gc
	 *            The {@link GC} to operate on.
	 * @param bounds
	 *            The available space for painting.
	 * @param render
	 *            <code>true</code> if the processing result should be painted to the {@link GC},
	 *            <code>false</code> if not (in case of pre calculation).
	 */
	protected void paintHTML(String html, GC gc, Rectangle bounds, boolean render) {
		final TagProcessingState state = new TagProcessingState();
		state.setStartingPoint(bounds.x, bounds.y);
		state.setRendering(render);

		Deque<SpanElement> spanStack = new LinkedList<>();

		// Collection<PaintInstruction> instructions = new ArrayList<>();
		Collection<LinePainter> lines = new ArrayList<>();

		// as we only care about html tags, we ignore control character
		String cleanedHtml = html.replaceAll(CONTROL_CHARACTER_REGEX, "");

		// we need to introduce a fake root tag, because otherwise we will get invalid XML
		// exceptions
		cleanedHtml = FAKE_ROOT_TAG_START + cleanedHtml + FAKE_ROOT_TAG_END;

		gc.setAntialias(SWT.DEFAULT);
		gc.setTextAntialias(SWT.DEFAULT);

		XMLEventReader parser = null;

		int availableWidth = bounds.width;
		Deque<Integer> listIndentation = new LinkedList<>();

		boolean listOpened = false;

		try {
			parser = factory.createXMLEventReader(new StringReader(cleanedHtml));

			LinePainter currentLine = null;
			while (parser.hasNext()) {
				XMLEvent event = parser.nextEvent();
				
				switch (event.getEventType()) {
					case XMLStreamConstants.END_DOCUMENT:
						parser.close();
						break;
					case XMLStreamConstants.START_ELEMENT:
						final StartElement element = event.asStartElement();
						String elementString = element.getName().toString();

						if (TAG_PARAGRAPH.equals(elementString)) {
							currentLine = createNewLine(lines);
							AlignmentStyle alignment = handleAlignmentConfiguration(element);
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state,
									new ParagraphInstruction(alignment, getParagraphSpace(), state));

							availableWidth -= alignment.marginLeft;
						}
						else if (TAG_BR.equals(elementString)) {
							currentLine = createNewLine(lines);
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new NewLineInstruction(state));
						}
						else if (TAG_SPAN.equals(elementString)) {
							PaintInstruction styleInstruction = handleStyleConfiguration(element, spanStack, state);
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, styleInstruction);
						}
						else if (TAG_STRONG.equals(elementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new BoldPaintInstruction(state));
						}
						else if (TAG_EM.equals(elementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new ItalicPaintInstruction(state));
						}
						else if (TAG_UNDERLINE.equals(elementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.setUnderlineActive(true);
								}
							});
						}
						else if (TAG_STRIKETHROUGH.equals(elementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.setStrikethroughActive(true);
								}
							});
						}
						else if (TAG_UNORDERED_LIST.equals(elementString)
								|| TAG_ORDERED_LIST.equals(elementString)) {
							int indent = calculateListIndentation(gc);
							availableWidth -= indent;
							listIndentation.add(indent);
							listOpened = true;

							AlignmentStyle alignment = handleAlignmentConfiguration(element);
							availableWidth -= alignment.marginLeft;

							boolean isOrderedList = TAG_ORDERED_LIST.equals(elementString);

							currentLine = createNewLine(lines);
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state,
									new ListInstruction(indent, isOrderedList, alignment, getParagraphSpace(), state));

							// inspect font attributes
							PaintInstruction styleInstruction = handleStyleConfiguration(element, spanStack, state);
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, styleInstruction);
						}
						else if (TAG_LIST_ITEM.equals(elementString)) {
							// if a list was opened before, the list tag created a new line
							// otherwise we create a new line for the new list item
							if (!listOpened) {
								currentLine = createNewLine(lines);
								currentLine = addInstruction(gc, availableWidth, lines,
										currentLine, state, new NewLineInstruction(state));
							}
							else {
								listOpened = false;
							}

							final AlignmentStyle alignment = handleAlignmentConfiguration(element);

							// paint number/bullet
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.resetX();

									String bullet = getBulletCharacter(state.getListDepth()) + "\u00a0";
									if (state.isOrderedList()) {
										bullet = "" + state.getCurrentListNumber() + ". ";
									}
									int extend = gc.textExtent(bullet).x;
									gc.drawText(bullet, state.getPointer().x - extend, state.getPointer().y, (state.hasPreviousBgColor()));

									state.setTextAlignment(alignment.alignment);
									state.calculateX(area.width);
								}
							});
						}

						break;
					case XMLStreamConstants.END_ELEMENT:
						String endElementString = event.asEndElement().getName().toString();
						if (TAG_PARAGRAPH.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state,
									new ResetParagraphInstruction(getParagraphSpace(), state));

							availableWidth = bounds.width;
						}
						else if (TAG_SPAN.equals(endElementString)) {
							SpanElement span = spanStack.pollLast();
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new ResetSpanStylePaintInstruction(state, span));
						}
						else if (TAG_STRONG.equals(endElementString)
								|| TAG_EM.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new ResetFontPaintInstruction(state));
						}
						else if (TAG_UNDERLINE.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.setUnderlineActive(false);
								}
							});
						}
						else if (TAG_STRIKETHROUGH.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.setStrikethroughActive(false);
								}
							});
						}
						else if (TAG_LIST_ITEM.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.increaseCurrentListNumber();
									state.setTextAlignment(TextAlignment.LEFT);
								}
							});
						}
						else if (TAG_ORDERED_LIST.equals(endElementString)
								|| TAG_UNORDERED_LIST.equals(endElementString)) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new PaintInstruction() {

								@Override
								public void paint(GC gc, Rectangle area) {
									state.resetListConfiguration();

									// if the last list layer was finished, increase the line height
									// like in paragraph
									if (state.getListDepth() == 0) {
										state.setMarginLeft(0);
										state.increaseY(state.getCurrentLineHeight());
										state.increaseY(getParagraphSpace());
									}

									state.resetX();
									state.setTextAlignment(TextAlignment.LEFT);
								}
							});

							availableWidth += listIndentation.pollLast();
							
							// set currentLine to null as any further content will start on a new line
							currentLine = null;
						}

						break;
					case XMLStreamConstants.CHARACTERS:
						Characters characters = event.asCharacters();
						String text = characters.getData();
						currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new TextPaintInstruction(state, text));
						break;
					case XMLStreamConstants.ENTITY_REFERENCE:
						String value = entityReplacer.getEntityReferenceValue((EntityReference) event);
						if (value != null) {
							currentLine = addInstruction(gc, availableWidth, lines, currentLine, state, new TextPaintInstruction(state, value));
						}
						break;
					case XMLStreamConstants.ATTRIBUTE:
						break;
					default:
						break;
				}
			}
		} catch (XMLStreamException e) {
			e.printStackTrace();
		} finally {
			if (parser != null) {
				try {
					parser.close();
				} catch (XMLStreamException e) {
					e.printStackTrace();
				}
			}
		}

		// initialize the state to be able to iterate over the line instructions
		state.setLineIterator(lines.iterator());

		preferredSize.x = bounds.width;
		preferredSize.y = 0;

		// perform the painting
		for (LinePainter painter : lines) {
			painter.paint(gc, bounds);
			preferredSize.x = Math.max(preferredSize.x, painter.getContentWidth());
			preferredSize.y += painter.getLineHeight();
		}
		// add paragraphSpace on top and bottom
		preferredSize.y += (2 * state.getParagraphCount() * getParagraphSpace());
	}

	private LinePainter addInstruction(
			GC gc, int availableWidth,
			Collection<LinePainter> lines, LinePainter currentLine,
			final TagProcessingState state, PaintInstruction instruction) {

		if (instruction instanceof FontMetricsProvider) {
			// apply the font to the temp GC
			((FontMetricsProvider) instruction).getFontMetrics(gc);
		}

		// if currentLine is null at this point, there is no spanning p tag and we create a new line
		// this is for convenience to support also simple text
		if (currentLine == null) {
			currentLine = createNewLine(lines);
			currentLine.addInstruction(new PaintInstruction() {

				@Override
				public void paint(GC gc, Rectangle area) {
					state.activateNextLine();
					state.increaseY(getParagraphSpace());
					state.increaseParagraphCount();
				}
			});
		}

		LinePainter lineToUse = currentLine;

		if (instruction instanceof TextPaintInstruction) {

			TextPaintInstruction txtInstr = (TextPaintInstruction) instruction;
			int textLength = txtInstr.getTextLength(gc);
			int trimmedTextLength = txtInstr.getTrimmedTextLength(gc);

			// this needs to be done to deal with an issue on the printer GC that strangely
			// changes the font metrics on textExtent()
			gc.setFont(gc.getFont());

			if ((currentLine.getContentWidth() + textLength) > availableWidth) {

				if (this.wordWrap) {
					// if word wrapping is enabled, split the text and create new lines
					// by making several TextPaintInstructions with substrings

					Deque<String> wordsToProcess = new LinkedList<>(Arrays.asList(txtInstr.getText().split("(?<=" + wordSplitRegex + ")")));
					String subString = "";
					int subStringLength = 0;
					while (!wordsToProcess.isEmpty()) {
						String word = wordsToProcess.pollFirst();
						int wordLength = gc.textExtent(word).x;
						subStringLength += wordLength;
						if ((lineToUse.getContentWidth() + subStringLength) > availableWidth) {
							boolean newLine = true;
							if (!subString.trim().isEmpty()) {
								// trim right side
								subString = ResourceHelper.rtrim(subString);

								txtInstr = new TextPaintInstruction(state, subString);
								textLength = txtInstr.getTextLength(gc);
								trimmedTextLength = txtInstr.getTrimmedTextLength(gc);

								lineToUse.addInstruction(txtInstr);

								lineToUse.increaseContentWidth(textLength);
								lineToUse.increaseTrimmedContentWidth(trimmedTextLength);
							}
							else if (lineToUse.getContentWidth() == 0) {
								// no content already but text width greater than available width
								// TODO 0.2 - modify text to show ...
								// TODO 0.2 - add trim to avoid empty lines because of spaces
								newLine = false;
							}
							
							subString = word;
							subStringLength = wordLength;

							if (newLine) {
								lineToUse = createNewLine(lines);
								lineToUse.addInstruction(new NewLineInstruction(state));
							}
						}
						else {
							subString += word;
						}
					}

					if (!subString.trim().isEmpty()) {
						txtInstr = new TextPaintInstruction(state, subString);
						textLength = txtInstr.getTextLength(gc);
						trimmedTextLength = txtInstr.getTrimmedTextLength(gc);
						instruction = txtInstr;
					}
				}

			}

			lineToUse.addInstruction(instruction);
			lineToUse.increaseContentWidth(textLength);
			lineToUse.increaseTrimmedContentWidth(trimmedTextLength);
		}
		else {
			lineToUse.addInstruction(instruction);
		}

		return lineToUse;
	}

	private LinePainter createNewLine(Collection<LinePainter> lines) {
		LinePainter currentLine = new LinePainter();
		lines.add(currentLine);
		return currentLine;
	}

	private AlignmentStyle handleAlignmentConfiguration(StartElement element) {
		AlignmentStyle result = new AlignmentStyle();
		for (Iterator<?> attributes = element.getAttributes(); attributes.hasNext();) {
			Attribute attribute = (Attribute) attributes.next();
			if (ATTRIBUTE_STYLE.equals(attribute.getName().toString())) {
				Map<String, String> styleProperties = getStyleProperties(attribute.getValue().toString());
				for (Map.Entry<String, String> entry : styleProperties.entrySet()) {
					if (ATTRIBUTE_PARAGRAPH_MARGIN_LEFT.equals(entry.getKey())) {
						String pixelValue = entry.getValue().replace("px", "");
						try {
							int pixel = Integer.valueOf(pixelValue.trim());
							result.marginLeft = pixel;
						} catch (NumberFormatException e) {
							// if the value is not a valid number value
							// we simply ignore it
							e.printStackTrace();
						}
					}
					else if (ATTRIBUTE_PARAGRAPH_TEXT_ALIGN.equals(entry.getKey())) {
						try {
							TextAlignment alignment = TextAlignment.valueOf(entry.getValue().toUpperCase());
							result.alignment = alignment;
						} catch (IllegalArgumentException e) {
							// if the value doesn't match a valid
							// text-aligment value we simply ignore it
							e.printStackTrace();
						}
					}
				}
			}
		}
		return result;
	}

	private PaintInstruction handleStyleConfiguration(StartElement element, Deque<SpanElement> spanStack, TagProcessingState state) {
		// create the span element with reset informations on tag close
		SpanElement span = new SpanElement();
		// create span style paint instruction that should be performed on
		// painting
		SpanStylePaintInstruction styleInstruction = new SpanStylePaintInstruction(state);

		// inspect the attributes
		for (Iterator<?> attributes = element.getAttributes(); attributes.hasNext();) {
			Attribute attribute = (Attribute) attributes.next();
			if (ATTRIBUTE_STYLE.equals(attribute.getName().toString())) {
				Map<String, String> styleProperties = getStyleProperties(attribute.getValue().toString());
				for (Map.Entry<String, String> entry : styleProperties.entrySet()) {
					if (ATTRIBUTE_STYLE_COLOR.equals(entry.getKey())) {
						// update the span element to know what to reset on tag
						// close
						span.types.add(SpanType.COLOR);
						// update the style paint instruction
						styleInstruction.setForegroundColor(ResourceHelper.getColor(entry.getValue()));
					}
					else if (ATTRIBUTE_STYLE_BACKGROUND_COLOR.equals(entry.getKey())) {
						// update the span element to know what to reset on tag
						// close
						span.types.add(SpanType.BG_COLOR);
						// update the style paint instruction
						styleInstruction.setBackgroundColor(ResourceHelper.getColor(entry.getValue()));
					}
					else if (ATTRIBUTE_STYLE_FONT_SIZE.equals(entry.getKey())) {
						// update the span element to know what to reset on tag
						// close
						span.types.add(SpanType.FONT);
						// update the style paint instruction
						String pixelValue = entry.getValue().replace("px", "");
						try {
							int pixel = Integer.valueOf(pixelValue.trim());
							// the size in pixels specified in HTML
							// so we need to convert it to point
							int pointSize = 72 * ScalingHelper.convertHorizontalPixelToDpi(pixel) / Display.getDefault().getDPI().x;
							styleInstruction.setFontSize(pointSize);
						} catch (NumberFormatException e) {
							e.printStackTrace();
						}
					}
					else if (ATTRIBUTE_STYLE_FONT_FAMILY.equals(entry.getKey())) {
						// update the span element to know what to reset on tag
						// close
						span.types.add(SpanType.FONT);
						// update the style paint instruction
						styleInstruction.setFontType(entry.getValue().split(",")[0]);
					}
				}
			}
		}

		spanStack.add(span);
		return styleInstruction;
	}

	private Map<String, String> getStyleProperties(String styleString) {
		Map<String, String> result = new HashMap<>();

		String[] configurations = styleString.split(";");
		for (String config : configurations) {
			String[] keyValuePair = config.split(":");
			result.put(keyValuePair[0].trim(), keyValuePair[1].trim());
		}

		return result;
	}

	/**
	 * Calculates the indentation to use for list items.
	 *
	 * @param gc
	 *            The current {@link GC} for calculating the text extend.
	 * @return The indentation to use for list items.
	 */
	protected int calculateListIndentation(GC gc) {
		return gc.textExtent("000. ").x;
	}

	/**
	 * @param listDepth
	 *            The list depth of the current list. Needs to be 1 for the top level list.
	 * @return The bullet character to use for list items of an unordered list.
	 */
	protected String getBulletCharacter(int listDepth) {
		if (listDepth >= BULLETS.length) {
			return BULLETS[BULLETS.length - 1];
		}
		return BULLETS[listDepth - 1];
	}

	/**
	 * Set an {@link EntityReplacer} that should be used to replace {@link EntityReference}s in the
	 * HTML snippet to parse.
	 *
	 * @param entityReplacer
	 *            The {@link EntityReplacer} to use.
	 */
	public void setEntityReplacer(EntityReplacer entityReplacer) {
		this.entityReplacer = entityReplacer;
	}

	/**
	 * Returns the preferred size of the content. It is calculated after processing the content.
	 *
	 * @return The preferred size of the content.
	 *
	 * @see RichTextPainter#preCalculate(String, GC, Rectangle, boolean)
	 */
	public Point getPreferredSize() {
		return this.preferredSize;
	}

	/**
	 * @return The space that should be used before and after a paragraph.<br>
	 *         <b>Note:</b> Between two paragraphs the paragraphSpace * 2 is added as space.
	 */
	public int getParagraphSpace() {
		return ScalingHelper.convertVerticalPixelToDpi(this.paragraphSpace);
	}

	/**
	 * @param paragraphSpace
	 *            The space that should be applied before and after a paragraph.<br>
	 *            <b>Note:</b> Between two paragraphs the paragraphSpace * 2 is added as space.
	 */
	public void setParagraphSpace(int paragraphSpace) {
		this.paragraphSpace = paragraphSpace;
	}
	
	/**
	 * @param wordSplitRegex
	 *            The regular expression that will be used to determine word boundaries. The default
	 *            is "\s".
	 * @since 1.3.0
	 */
	public void setWordSplitRegex(String wordSplitRegex) {
		this.wordSplitRegex = wordSplitRegex;
	}
}