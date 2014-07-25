package edu.isi.karma.controller.command.selection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.python.core.PyCode;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.controller.command.transformation.PythonRepository;
import edu.isi.karma.controller.command.transformation.PythonTransformationHelper;
import edu.isi.karma.er.helper.CloneTableUtils;
import edu.isi.karma.rep.HTable;
import edu.isi.karma.rep.Node;
import edu.isi.karma.rep.Row;
import edu.isi.karma.rep.Table;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.rep.Workspace;

public class Selection {
	public enum SelectionStatus {
		UP_TO_DATE, OUT_OF_DATE
	}
	
	public enum Tag {
		IGNORE_IN_PUBLISH_RDF, IGNORE_IN_JSON_EXPORT, 
		IGNORE_IN_SERVICE_INVOCATION, IGNORE_IN_WORKSHEET_TRANSFORMATION
	}
	
	public class SelectionProperty {
		public Boolean selected;
		public String pythonCode;
		public SelectionProperty(boolean selected, String pythonCode) {
			this.selected = selected;
			this.pythonCode = pythonCode;
		}
	}
	private static Logger logger = LoggerFactory
			.getLogger(Selection.class);
	
	@SuppressWarnings("unused")
	private SelectionStatus status;
	private Workspace workspace;
	private String worksheetId;
	private List<Tag> tags = new ArrayList<Tag>();
	private Map<Row, SelectionProperty> selectedRows = new HashMap<Row, SelectionProperty>();
	
	public Selection(Workspace workspace, String worksheetId) {
		this.worksheetId = worksheetId;
		this.workspace = workspace;
	}
	public void addSelections(HTable htable, String pythonCode) throws IOException {
		List<Table> tables = new ArrayList<Table>();
		Worksheet worksheet = workspace.getWorksheet(worksheetId);
		CloneTableUtils.getDatatable(worksheet.getDataTable(), htable, tables);
		PythonInterpreter interpreter = new PythonInterpreter();
		PyCode code = getCompiledCode(pythonCode, interpreter);
		for (Table t : tables) {
			for (Row r : t.getRows(0, t.getNumRows())) {
				selectedRows.put(r, new SelectionProperty(evaluatePythonExpression(r, code, interpreter), pythonCode));
			}
		}
	}
	
	public Map<Row, SelectionProperty> getSelectedRows() {
		return selectedRows;
	}
	
	public void Intersect(Selection source) {
		for (Entry<Row, SelectionProperty> entry : this.selectedRows.entrySet()) {
			Row key = entry.getKey();
			SelectionProperty value = entry.getValue();
			if (source.getSelectedRows().containsKey(key)) {
				value.selected = value.selected & source.selectedRows.get(key).selected;
			}
			else
				value.selected = false;
		}
	}
	
	public void Subtract(Selection source) {
		for (Entry<Row, SelectionProperty> entry : this.selectedRows.entrySet()) {
			Row key = entry.getKey();
			SelectionProperty value = entry.getValue();
			if (source.getSelectedRows().containsKey(key) && value.selected) {
				value.selected = value.selected ^ source.selectedRows.get(key).selected;
			}
		}
	}
	
	public void Invert() {
		for (Entry<Row, SelectionProperty> entry : this.selectedRows.entrySet()) {
			SelectionProperty value = entry.getValue();
			value.selected = !value.selected;
		}
	}
	
	public void setTags(List<Tag> tags) {
		this.tags.clear();
		this.tags.addAll(tags);
	}
	
	public void updateSelection() throws IOException {
		for (Entry<Row, SelectionProperty> entry : this.selectedRows.entrySet()) {
			Row key = entry.getKey();
			SelectionProperty value = entry.getValue();
			PythonInterpreter interpreter = new PythonInterpreter();
			value.selected = evaluatePythonExpression(key, getCompiledCode(value.pythonCode, interpreter), interpreter);
		}
	}
	
	public void addInputColumns(String hNodeId) {
		System.out.println(hNodeId);
	}
	
	
	private boolean evaluatePythonExpression(Row r, PyCode code, PythonInterpreter interpreter) {
		ArrayList<Node> nodes = new ArrayList<Node>(r.getNodes());
		Node node = nodes.get(0);
		interpreter.set("nodeid", node.getId());
		PyObject output = interpreter.eval(code);
		return PythonTransformationHelper.getPyObjectValueAsBoolean(output);
	}
	
	private PyCode getCompiledCode(String pythonCode, PythonInterpreter interpreter) throws IOException {
		
		String trimmedTransformationCode = pythonCode.trim();
		Worksheet worksheet = workspace.getWorksheet(worksheetId);
		if (trimmedTransformationCode.isEmpty()) {
			trimmedTransformationCode = "return False";
		}
		String transformMethodStmt = PythonTransformationHelper
				.getPythonTransformMethodDefinitionState(worksheet,
						trimmedTransformationCode);


		logger.debug("Executing PySelection\n" + transformMethodStmt);

		// Prepare the Python interpreter
		PythonRepository repo = PythonRepository.getInstance();
		repo.initializeInterperter(interpreter);
		repo.importUserScripts(interpreter);
		
		repo.compileAndAddToRepositoryAndExec(interpreter, transformMethodStmt);
		
		interpreter.set("workspaceid", workspace.getId());
		interpreter.set("command", this);
		return repo.getTransformCode();
	}
	
	
}
