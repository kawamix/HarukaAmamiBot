package com.kawamix.combined.ngrammodel.forsudachi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.stream.Collectors;

import com.kawamix.serialize.Serialize;
import com.kawamix.serialize.Serialize.TYPE;
import com.kawamix.sudachi.SudachiTokenizer;
import com.worksap.nlp.sudachi.Morpheme;

/**
 * N-gram言語モデル for Sudachi
 * @author kawami
 *
 */
public class NgramLanguageModel {
	private int n; //the n of ngram
	private SudachiTokenizer tokenizer;
	private Map<SudachiNodeKey, List<SudachiNode>> chainMap = new HashMap<>();
	private Map<String, Integer> wordFreqMap = new HashMap<>();

	public NgramLanguageModel(int n) {
		this.n = n;
	}

	public void setTokenizer(SudachiTokenizer tokenizer) {
		this.tokenizer = tokenizer;
	}

	public void save(String path) {
		try {
			Serialize.saveObject(path, this.chainMap, TYPE.DEFAULT);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void loadSerializedFile(String path) {
		try {
			System.out.println("loading featured LM...");
			//			this.chainMap = (HashMap) Serialize.loadObject(path);
			this.chainMap = (Map) Serialize.loadObject(path);
			countFrequency();

		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	private void countFrequency() {
		for (List<SudachiNode> nodeList : this.chainMap.values()) {
			for (SudachiNode node : nodeList) {
				Morpheme morpheme = node.getLastToken();
				if (morpheme == null)
					continue;
				String surface = morpheme.surface();
				Integer freq = this.wordFreqMap.get(surface);
				if (freq == null)
					freq = 1;
				else
					freq = freq + 1;
				wordFreqMap.put(surface, freq);
			}
		}
	}

	public int getFreq(String surface) {
		Integer freq = this.wordFreqMap.get(surface);
		return freq == null ? 0 : freq;
	}

	public void loadDirectory(String directoryPath) {
		Path directory = Paths.get(directoryPath);
		loadDirectory(directory);
	}

	public void loadDirectory(Path directoryPath) {
		if (!Files.isDirectory(directoryPath)) {
			System.err.println(directoryPath + " is not a directory!");
			return;
		}
		try {
			List<Path> files = Files.list(directoryPath).collect(Collectors.toList());
			for (Path file : files) {
				loadFile(file);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public void loadFile(String filePath) throws IOException {
		loadFile(Paths.get(filePath));
	}

	public void loadFile(Path path) throws IOException {
		List<String> lines = Files.readAllLines(path);
		loadLines(lines);
	}

	public void loadLines(List<String> lines) {
		List<Entry<SudachiNodeKey, SudachiNode>> SudachiNodeEntries = new ArrayList<>();
		for (String line : lines) {
			buildNgramModel(line, SudachiNodeEntries);
		}
		registerNgram(SudachiNodeEntries);
		countFrequency();
	}

	private String trim(String line) {
		line = line.replace("｢", "「");
		line = line.replace("｣", "");
		return line;
	}

	private void buildNgramModel(String line, List<Entry<SudachiNodeKey, SudachiNode>> SudachiNodeEntries) {
		if (tokenizer == null) {

		}
		try {
			// n = 3 -> [null, A, B], [A, B, C], [B, C, null]
			line = trim(line);
			List<Morpheme> tokens = tokenizer.tokenize(line.replace("\t", ""));
			int SudachiNodeSize = tokens.size() - n + 1 + 1; // 文頭表現と文末表現の分をそれぞれ1回ずつ足す

			for (int i = -1; i < SudachiNodeSize; i++) {
				Morpheme data[] = new Morpheme[n];

				if (i == -1) { // 文頭
					for (int j = 1; j < data.length && i + j < tokens.size(); j++) {
						data[j] = tokens.get(i + j);
					}
				} else if (i == SudachiNodeSize - 1) { // 文末
					for (int j = 0; j < data.length - 1 && i + j < tokens.size(); j++) {
						data[j] = tokens.get(i + j);
					}
				} else {
					for (int j = 0; j < data.length && i + j < tokens.size(); j++) {
						data[j] = tokens.get(i + j);
					}
				}

				SudachiNodeKey key = new SudachiNodeKey(data);
				SudachiNode value = new SudachiNode(data);
				SudachiNodeEntries.add(new SimpleEntry<>(key, value));
			}
		} catch (IllegalArgumentException e) {
		}
	}

	private void registerNgram(List<Entry<SudachiNodeKey, SudachiNode>> SudachiNodeEntries) {
		while (SudachiNodeEntries.size() > 0) {
			SudachiNodeKey targetKey = SudachiNodeEntries.get(0).getKey();
			List<SudachiNode> SudachiNodeList = this.chainMap.get(targetKey);
			if (SudachiNodeList == null) {
				SudachiNodeList = new ArrayList<>();
				this.chainMap.put(targetKey, SudachiNodeList);
			}
			List<Entry<SudachiNodeKey, SudachiNode>> copies = new ArrayList<>(SudachiNodeEntries);
			int count = 0;
			for (int i = 0; i < copies.size(); i++) {
				Entry<SudachiNodeKey, SudachiNode> entry = copies.get(i);
				SudachiNodeKey key = entry.getKey();
				if (!key.equals(targetKey)) {
					continue;
				}
				SudachiNode value = entry.getValue();
				int index = SudachiNodeList.indexOf(value);
				if (index == -1) {
					SudachiNodeList.add(value);
				} else {
					value = SudachiNodeList.get(index);
					value.addFreq();
				}
				//				System.out.println(
				//						"key:" + key + " - " + ", value:" + value + ", freq:" + value.getFreq() + " "
				//								+ Thread.currentThread().getStackTrace()[1]);
				SudachiNodeEntries.remove(i - count++);
			}
		}

	}

	public List<Entry<SudachiNodeKey, List<SudachiNode>>> findHead() {
		List<Entry<SudachiNodeKey, List<SudachiNode>>> heads = new ArrayList<>();
		for (Entry<SudachiNodeKey, List<SudachiNode>> entry : chainMap.entrySet()) {
			SudachiNodeKey key = entry.getKey();
			if (key.isBOS()) {
				heads.add(entry);
			}
		}
		return heads;
	}

	public SudachiNode findNextSample(SudachiNode SudachiNode) {
		SudachiNodeKey key = convertToSudachiNodeKey(SudachiNode);
		List<SudachiNode> candidates = findNextCandidates(key);
		if (candidates == null)
			return null;
		return candidates.get(new Random().nextInt(candidates.size()));
	}

	private SudachiNodeKey convertToSudachiNodeKey(SudachiNode SudachiNode) {
		Morpheme[] tokens = SudachiNode.getValue();
		Morpheme[] keyMorphemes = new Morpheme[tokens.length];
		for (int i = 0; i < n - 1; i++) {
			keyMorphemes[i] = tokens[i + 1];
		}
		return new SudachiNodeKey(keyMorphemes);
	}

	public List<SudachiNode> findNextCandidates(SudachiNode SudachiNode) {
		return findNextCandidates(convertToSudachiNodeKey(SudachiNode));
	}

	public List<SudachiNode> findNextCandidates(SudachiNodeKey key) {
		//		System.out.println("key:" + key + " = " + chainMap.get(key));
		if (chainMap == null)
			return null;
		return chainMap.get(key);
	}

}
