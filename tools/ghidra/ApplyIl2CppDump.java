// Java port of Il2CppDumper's ghidra_with_struct.py for Ghidra 12 (the .py is Python 2 and
// no longer runs). Also parses il2cpp.h first, so no "Parse C Source" step is needed.
// Usage (headless): -postScript ApplyIl2CppDump.java <folder with script.json and il2cpp.h>
//@category Il2Cpp
import java.io.*;
import java.nio.charset.StandardCharsets;

import com.google.gson.*;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.function.FunctionRenameOption;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.cparser.C.CParserUtils;
import ghidra.app.util.cparser.C.CParserUtils.CParseResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.symbol.SourceType;

public class ApplyIl2CppDump extends GhidraScript {

	private Address base;

	@Override
	protected void run() throws Exception {
		File dir;
		String[] args = getScriptArgs();
		if (args.length > 0) {
			dir = new File(args[0]);
		}
		else {
			dir = askDirectory("Folder with script.json and il2cpp.h", "Use");
		}
		File json = new File(dir, "script.json");
		File header = new File(dir, "il2cpp.h");
		base = currentProgram.getImageBase();

		println("[1/6] Parsing il2cpp.h (types)...");
		monitor.setMessage("Parsing il2cpp.h");
		CParseResults res = CParserUtils.parseHeaderFiles(new DataTypeManager[0],
			new String[] { header.getAbsolutePath() }, new String[0], new String[0],
			currentProgram.getDataTypeManager(), monitor);
		println("      header parse ok: " + (res != null && res.successful()));

		println("[2/6] Reading script.json...");
		JsonObject data;
		try (Reader r = new InputStreamReader(new FileInputStream(json), StandardCharsets.UTF_8)) {
			data = JsonParser.parseReader(r).getAsJsonObject();
		}

		JsonArray methods = arr(data, "ScriptMethod");
		println("[3/6] Method names: " + methods.size());
		monitor.initialize(methods.size());
		for (JsonElement e : methods) {
			monitor.checkCancelled();
			JsonObject m = e.getAsJsonObject();
			setName(addr(m, "Address"), m.get("Name").getAsString());
			monitor.incrementProgress(1);
		}

		JsonArray strings = arr(data, "ScriptString");
		println("[4/6] Strings: " + strings.size());
		monitor.initialize(strings.size());
		int index = 1;
		for (JsonElement e : strings) {
			monitor.checkCancelled();
			JsonObject s = e.getAsJsonObject();
			Address a = addr(s, "Address");
			setName(a, "StringLiteral_" + index++);
			try {
				setEOLComment(a, s.get("Value").getAsString());
			}
			catch (Exception ex) {
				// ignore
			}
			monitor.incrementProgress(1);
		}

		JsonArray metas = arr(data, "ScriptMetadata");
		JsonArray metaMethods = arr(data, "ScriptMetadataMethod");
		println("[5/6] Metadata: " + metas.size() + " + " + metaMethods.size());
		monitor.initialize(metas.size() + metaMethods.size());
		for (JsonElement e : metas) {
			monitor.checkCancelled();
			JsonObject m = e.getAsJsonObject();
			Address a = addr(m, "Address");
			String name = m.get("Name").getAsString();
			setName(a, name);
			try {
				setEOLComment(a, name);
			}
			catch (Exception ex) {
				// ignore
			}
			JsonElement sig = m.get("Signature");
			if (sig != null && !sig.isJsonNull() && !sig.getAsString().isEmpty()) {
				setType(a, sig.getAsString());
			}
			monitor.incrementProgress(1);
		}
		for (JsonElement e : metaMethods) {
			monitor.checkCancelled();
			JsonObject m = e.getAsJsonObject();
			Address a = addr(m, "Address");
			String name = m.get("Name").getAsString();
			setName(a, name);
			try {
				setEOLComment(a, name);
			}
			catch (Exception ex) {
				// ignore
			}
			monitor.incrementProgress(1);
		}

		JsonArray addresses = arr(data, "Addresses");
		println("[6/6] Functions: " + addresses.size() + ", then signatures: " + methods.size());
		monitor.initialize(addresses.size() + methods.size());
		for (int i = 0; i < addresses.size() - 1; i++) {
			monitor.checkCancelled();
			Address a = base.add(addresses.get(i).getAsLong());
			if (getFunctionAt(a) == null) {
				try {
					createFunction(a, null);
				}
				catch (Exception ex) {
					// ignore
				}
			}
			monitor.incrementProgress(1);
		}

		int ok = 0, bad = 0;
		for (JsonElement e : methods) {
			monitor.checkCancelled();
			JsonObject m = e.getAsJsonObject();
			String sig = m.get("Signature").getAsString();
			if (sig.endsWith(";")) {
				sig = sig.substring(0, sig.length() - 1);
			}
			String name = m.get("Name").getAsString().replace(' ', '-');
			try {
				FunctionDefinitionDataType fdt =
					CParserUtils.parseSignature((ghidra.app.services.DataTypeManagerService) null,
						currentProgram, sig, false);
				if (fdt != null) {
					fdt.setName(name);
					new ApplyFunctionSignatureCmd(addr(m, "Address"), fdt, SourceType.USER_DEFINED,
						false, FunctionRenameOption.RENAME).applyTo(currentProgram, monitor);
					ok++;
				}
				else {
					bad++;
				}
			}
			catch (Exception | Error ex) {
				bad++;
			}
			monitor.incrementProgress(1);
		}
		println("Signatures applied: " + ok + ", skipped: " + bad);
		println("Script finished!");
	}

	private static JsonArray arr(JsonObject data, String key) {
		JsonElement e = data.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	private Address addr(JsonObject o, String key) {
		return base.add(o.get(key).getAsLong());
	}

	private void setName(Address a, String name) {
		try {
			createLabel(a, name.replace(' ', '-'), true, SourceType.USER_DEFINED);
		}
		catch (Exception ex) {
			// ignore
		}
	}

	private void setType(Address a, String type) {
		String t = type.replace("*", " *").replace("  ", " ").trim();
		DataType dt = null;
		DataType[] found = getDataTypes(t);
		if (found.length == 1) {
			dt = found[0];
		}
		else if (found.length == 0 && t.endsWith(" *")) {
			DataType[] baseTypes = getDataTypes(t.substring(0, t.length() - 2));
			if (baseTypes.length == 1) {
				DataTypeManager dtm = currentProgram.getDataTypeManager();
				dt = dtm.addDataType(dtm.getPointer(baseTypes[0]), null);
			}
		}
		if (dt == null) {
			return;
		}
		try {
			createData(a, dt);
		}
		catch (Exception ex) {
			// ignore
		}
	}
}
