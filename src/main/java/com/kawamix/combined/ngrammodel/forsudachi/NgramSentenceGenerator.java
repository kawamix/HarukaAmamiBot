package com.kawamix.combined.ngrammodel.forsudachi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import com.kawamix.sudachi.SudachiTokenizer;
import com.kawamix.word2vec.Word2VecModel;
import com.worksap.nlp.sudachi.Morpheme;

/*
 * !! for Sudachi !!
 */
public class NgramSentenceGenerator {
	/**
	 * Ngram言語モデル
	 */
	private NgramLanguageModel generalLanguageModel;
	private NgramLanguageModel featuredLanguageModel;

	/**
	 * Tokenizer (Sudachi)
	 */
	private SudachiTokenizer tokenizer;

	/**
	 * Word2VecModel
	 */
	private Word2VecModel word2VecModel;

	/**
	 * N-gramのN (デフォルトは2)
	 */
	private int n = 2;

	/**
	 * 各種閾値
	 */
	private final double minCosineSim = 0.4; //形態素の置換処理における単語の最低類似度
	private final int maxSearchSudachiNodeSize = 3; //形態素の追加処理で先読みするノード数
	private final int maxSudachiNodeSize = n + 100; // 生成時の最長ノード数

	private enum FEATUREDSTATUS {
		REPLACE, ADD, DELETE, NONE
	}

	public enum GENERATEMODE {
		CHARACTERIZE, CHAT
	}

	private FEATUREDSTATUS currentStatus = FEATUREDSTATUS.NONE;
	private GENERATEMODE currentMode = GENERATEMODE.CHAT;

	public NgramSentenceGenerator() {

	}

	public void setN(int n) {
		this.n = n;
	}

	public void setTokenizer(SudachiTokenizer tokenizer) {
		this.tokenizer = tokenizer;
	}

	public void setWord2VecModel(Word2VecModel word2VecModel) {
		this.word2VecModel = word2VecModel;
	}

	public NgramLanguageModel buildGeneralModel(String path) {
		return buildModel(generalLanguageModel, path);
	}

	public void setMode(GENERATEMODE mode) {
		this.currentMode = mode;
	}

	/**
	 * This method is deprecated, so please use setFeaturedModel().
	 * @param path
	 */
	public NgramLanguageModel buildFeturedModel(String path) {
		return buildModel(featuredLanguageModel, path);
	}

	private NgramLanguageModel buildModel(NgramLanguageModel languageModel, String path) {
		languageModel = new NgramLanguageModel(n);
		if (this.tokenizer != null) {
			languageModel.setTokenizer(tokenizer);
		}
		try {
			Path gotPath = Paths.get(path);
			if (Files.isDirectory(gotPath)) {
				languageModel.loadDirectory(path);
			} else if (Files.isRegularFile(gotPath)) {
				languageModel.loadFile(gotPath);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return languageModel;
	}

	private List<Entry<SudachiNodeKey, List<SudachiNode>>> getHeadList(NgramLanguageModel languageModel, int min) {
		List<Entry<SudachiNodeKey, List<SudachiNode>>> allHeadList = languageModel.findHead();
		List<Entry<SudachiNodeKey, List<SudachiNode>>> selected = new ArrayList<>();
		allHeadList.stream().filter(e -> e.getValue().size() > min).forEach(selected::add);
		Collections.sort(selected, new Comparator<Entry<SudachiNodeKey, List<SudachiNode>>>() {
			@Override
			public int compare(Entry<SudachiNodeKey, List<SudachiNode>> o1,
					Entry<SudachiNodeKey, List<SudachiNode>> o2) {
				return ((Integer) o2.getValue().size()).compareTo(o1.getValue().size());
			}
		});
		return selected;
	}

	public String generateText() {
		return generateText(true);
	}

	public String generateText(boolean characterized) {
		List<SudachiNode> nodeList = null;
		for (int i = 0; i < 10; i++) {
			SudachiNode nextSudachiNode = null;
			nodeList = new ArrayList<>();
			boolean usedFeaturedModel = !characterized;
			while (true) {
				if (!usedFeaturedModel && currentStatus != FEATUREDSTATUS.NONE)
					usedFeaturedModel = true;
				if (nodeList.size() > maxSudachiNodeSize)
					break;
				if (nextSudachiNode != null && nextSudachiNode.isEOS())
					break;
				if (nodeList.size() > 0 && nextSudachiNode == null) {
					nodeList = null;
					break;
				}
				//											System.out.println("nextSudachiNode:" + nextSudachiNode);
				nextSudachiNode = generateNextSudachiNode(nextSudachiNode, nodeList, nextSudachiNode == null);
			}
			if (nodeList == null)
				continue;
			int totalScore = 0;
			for (SudachiNode node : nodeList) {
				totalScore += node.getFreq();
			}
			if (usedFeaturedModel && ((double) totalScore / (double) nodeList.size()) > 4.0)
				break;
		}
		if (nodeList == null && characterized)
			return generateText(false);

		return mergeSudachiNodeList(nodeList);
	}

	public String generateText(NgramLanguageModel featuredLanguageModel) {
		this.featuredLanguageModel = featuredLanguageModel;
		String text = generateText();
		return text;
	}

	public String generateText(NgramLanguageModel featuredLanguageModel, NgramLanguageModel generalLanguageModel) {
		this.featuredLanguageModel = featuredLanguageModel;
		this.generalLanguageModel = generalLanguageModel;
		String text = generateText();
		return text;
	}

	private String mergeSudachiNodeList(List<SudachiNode> nodeList) {
		System.out.println(nodeList);
		if (nodeList == null)
			return null;
		StringBuilder sb = new StringBuilder();
		SudachiNode bos = nodeList.get(0);
		String[] bosStrings = bos.getString();
		for (int i = 1; i < bosStrings.length; i++) {
			sb.append(bosStrings[i]);
		}
		for (int i = 1; i < nodeList.size() - 1; i++) {
			SudachiNode node = nodeList.get(i);

			String[] strings = node.getString();
			sb.append(strings[strings.length - 1]);

		}
		if (sb.toString().contains("null"))
			return sb.toString().replace("null", "");
		return sb.toString();
	}

	private SudachiNode generateNextSudachiNode(SudachiNode node, List<SudachiNode> nodeList, boolean isFirst) {
		if (!isFirst) { // 先頭のノードを探索する段階
			// 汎用言語モデルよりSudachiNodeの候補を取得
			List<SudachiNode> candidates = new ArrayList<>();
			generalLanguageModel.findNextCandidates(node).stream().forEach(n -> {

				Morpheme morpheme = n.getLastToken();
				if (!nodeList.contains(n) && (morpheme == null || (this.currentMode != GENERATEMODE.CHAT
						|| generalLanguageModel.getFreq(morpheme.surface()) > 1)))
					candidates.add(n);
			});
			return generateNextSudachiNode(node, nodeList, candidates);
		}
		List<Entry<SudachiNodeKey, List<SudachiNode>>> allHeadList = getHeadList(generalLanguageModel,
				this.currentMode == GENERATEMODE.CHAT ? 1 : 0);
		if (allHeadList.size() < 1) {
			nodeList.add(null);
			return null;
		}
		List<SudachiNode> candidates = new ArrayList<>();
		for (int i = 0; i < 10 && i < allHeadList.size(); i++) {
			candidates.addAll(allHeadList.get(i).getValue());
		}
		return generateNextSudachiNode(node, nodeList, candidates);
	}

	private SudachiNode generateNextSudachiNode(SudachiNode node, List<SudachiNode> nodeList,
			List<SudachiNode> candidates) {
		System.out.println("node:" + node + ", nodeList:" + nodeList);

		// 特徴言語モデルより置換・追加・削除用の候補を取得
		/**
		 * 置換・追加・削除の優先順位
		 */
		boolean featured = true;
		if (featured) {
			Random rnd = new Random();
			// 形態素の置換処理
			// 同じN-1個の形態素をキーとするノードを取得
			long start = System.currentTimeMillis();
			if (currentStatus != FEATUREDSTATUS.REPLACE && rnd.nextInt(20) != 0) {
				Entry<SudachiNode, SudachiNode> replaced = getReplacedSudachiNode(candidates, node);
				System.out.println("\treplace node:" + (System.currentTimeMillis() - start) + "ms");
				if (replaced != null) {
					//置換
					System.out.println("rep token:" + replaced.getKey().getLastToken().partOfSpeech() + "\t"
							+ replaced.getValue().getLastToken().partOfSpeech());
					nodeList.add(replaced.getValue());
					currentStatus = FEATUREDSTATUS.REPLACE;
					return replaced.getKey();
				}

			}

			// 形態素の追加処理
			// 限界探索数先まで特徴言語モデルのほうのノードを探索
			start = System.currentTimeMillis();
			if (node != null && rnd.nextInt(5) != 0) {
				Entry<SudachiNode, List<SudachiNode>> added = getAddedSudachiNode(candidates, node);
				System.out.println("\tadded node:" + (System.currentTimeMillis() - start) + "ms");
				if (added != null) {
					//追加
					nodeList.addAll(added.getValue());
					currentStatus = FEATUREDSTATUS.ADD;
					return added.getKey();
				}

			}
			// 形態素の削除処理
			start = System.currentTimeMillis();
			if (rnd.nextInt(10) != 0) {
				Entry<SudachiNode, SudachiNode> deleted = getDeletedSudachiNode(candidates, node);
				System.out.println("\tdeleted node:" + (System.currentTimeMillis() - start) + "ms");
				if (deleted != null) {
					// 削除
					nodeList.add(deleted.getValue());
					currentStatus = FEATUREDSTATUS.DELETE;
					return deleted.getKey();
				}

			}
		}

		Collections.sort(candidates, new Comparator<SudachiNode>() {
			@Override
			public int compare(SudachiNode o1, SudachiNode o2) {
				return ((Integer) o2.getFreq()).compareTo(o1.getFreq());
			}
		});
		Random rnd = new Random();
		if (candidates.size() < 1)
			return null;
		int index = candidates.size() > 5 ? rnd.nextInt(5) : rnd.nextInt(candidates.size());
		SudachiNode next = candidates.get(index);
		nodeList.add(next);
		currentStatus = FEATUREDSTATUS.NONE;
		return next;
	}

	private Entry<SudachiNode, SudachiNode> getDeletedSudachiNode(List<SudachiNode> candidates,
			SudachiNode keySudachiNode) {
		SudachiNode selected = null, original = null;
		double highScore = 0.0d;

		for (SudachiNode candidate : candidates) {
			List<SudachiNode> featuredSudachiNodeCandidates = null;
			if (keySudachiNode == null) {
				List<Entry<SudachiNodeKey, List<SudachiNode>>> allHeadList = getHeadList(featuredLanguageModel, 0);
				for (Entry<SudachiNodeKey, List<SudachiNode>> entry : allHeadList) {
					if (entry.getKey().equals(getSudachiNodeKey(candidate))) {
						candidates = entry.getValue();
						break;
					}
				}
			} else {
				featuredSudachiNodeCandidates = featuredLanguageModel.findNextCandidates(keySudachiNode);
			}
			if (featuredSudachiNodeCandidates == null || candidate.isEOS())
				continue;
			List<Entry<SudachiNode, SudachiNode>> entries = deleteFeaturedSudachiNode(candidate,
					featuredSudachiNodeCandidates);
			for (int i = 0; i < 10 && i < entries.size(); i++) {
				Entry<SudachiNode, SudachiNode> entry = entries.get(i);
				SudachiNode featuredSudachiNode = entry.getKey();
				SudachiNode generalSudachiNode = entry.getValue();
				double score = (featuredSudachiNode.getFreq() * 0.6 + generalSudachiNode.getFreq() * 0.4)
						* (1 + new Random().nextDouble());
				if (highScore < score) {
					original = generalSudachiNode;
					selected = featuredSudachiNode;
				}
			}
		}
		if (original == null)
			return null;
		return new SimpleEntry<>(original, selected);
	}

	private List<Entry<SudachiNode, SudachiNode>> deleteFeaturedSudachiNode(SudachiNode node,
			List<SudachiNode> featuredSudachiNodeCandidates) {
		List<SudachiNode> candidates = generalLanguageModel.findNextCandidates(node);
		List<Entry<SudachiNode, SudachiNode>> outputSudachiNodePairList = new ArrayList<>();

		for (SudachiNode candidate : candidates) {
			Morpheme generalLastToken = candidate.getLastToken();
			for (SudachiNode featuredSudachiNodeCandidate : featuredSudachiNodeCandidates) {
				Morpheme featuredLastToken = featuredSudachiNodeCandidate.getLastToken();
				if (!equalsMorpheme(generalLastToken, featuredLastToken))
					continue;
				// matches!
				outputSudachiNodePairList.add(new SimpleEntry<>(featuredSudachiNodeCandidate, candidate));
			}
		}
		Collections.sort(outputSudachiNodePairList, new Comparator<Entry<SudachiNode, SudachiNode>>() {
			@Override
			public int compare(Entry<SudachiNode, SudachiNode> o1, Entry<SudachiNode, SudachiNode> o2) {
				return ((Integer) o2.getKey().getFreq()).compareTo(o1.getKey().getFreq());
			}
		});
		return outputSudachiNodePairList;
	}

	private Entry<SudachiNode, List<SudachiNode>> getAddedSudachiNode(List<SudachiNode> candidates,
			SudachiNode keySudachiNode) {
		double highScore = 0.0d;
		List<SudachiNode> selected = null;
		SudachiNode original = null;
		for (SudachiNode candidate : candidates) {
			Entry<List<SudachiNode>, Double> entry = addFeaturedSudachiNodeList(candidate, keySudachiNode);
			double score = entry.getValue();
			if (highScore < score) {
				original = candidate;
				selected = entry.getKey();
			}
		}
		if (original == null)
			return null;
		return new SimpleEntry<>(original, selected);
	}

	private Entry<List<SudachiNode>, Double> addFeaturedSudachiNodeList(SudachiNode node, SudachiNode keySudachiNode) {
		Morpheme generalLastToken = node.getLastToken();

		//指定した値まで最大探索
		List<List<SudachiNode>> connectableSudachiNodeList = getConnectableFeaturedSudachiNodeList(keySudachiNode,
				generalLastToken,
				new ArrayList<>(), new ArrayList<>());
		double highScore = 0.0d;
		List<SudachiNode> selected = null;
		for (List<SudachiNode> nodeList : connectableSudachiNodeList) {
			double score = 0.0d;
			int nodeSize = nodeList.size();
			for (SudachiNode n : nodeList) {
				score += nodeSize * 0.5 + n.getFreq();
			}
			score = score / (nodeList.size());
			if (highScore < score) {
				highScore = score;
				selected = nodeList;
			}
		}
		return new SimpleEntry<>(selected, highScore);
	}

	private List<List<SudachiNode>> getConnectableFeaturedSudachiNodeList(SudachiNode nextSudachiNode,
			Morpheme lastMorpheme,
			List<SudachiNode> currentSudachiNodeList,
			List<List<SudachiNode>> connectedSudachiNodeList) {

		List<SudachiNode> candidates = featuredLanguageModel.findNextCandidates(nextSudachiNode);

		if (candidates == null) {
			return connectedSudachiNodeList;
		}
		for (SudachiNode candidate : candidates) {
			Morpheme featuredLastToken = candidate.getLastToken();
			List<SudachiNode> nodeList = new ArrayList<>(currentSudachiNodeList);
			nodeList.add(candidate);
			if (nodeList.size() > 1 && equalsMorpheme(lastMorpheme, featuredLastToken)) {
				connectedSudachiNodeList.add(nodeList);
				return connectedSudachiNodeList;
			} else {
				if (nodeList.size() > maxSearchSudachiNodeSize)
					return connectedSudachiNodeList;
				getConnectableFeaturedSudachiNodeList(candidate, lastMorpheme, nodeList,
						connectedSudachiNodeList);
			}
		}
		return connectedSudachiNodeList;
	}

	private boolean equalsMorpheme(Morpheme token1, Morpheme token2) {
		if (token1 == null || token2 == null)
			return token1 == null && token2 == null;
		if (!token1.surface().equals(token2.surface()))
			return false;
		if (!token1.partOfSpeech().equals(token2.partOfSpeech()))
			return false;
		return true;
	}

	private Entry<SudachiNode, SudachiNode> getReplacedSudachiNode(List<SudachiNode> candidates,
			SudachiNode keySudachiNode) {
		if (word2VecModel == null)
			return null;
		SudachiNode replaced = null, original = null;
		double highScore = 0.0d;
		for (SudachiNode candidate : candidates) {
			if (candidate.isEOS())
				continue;
			SudachiNode replacedCandidate = replaceFeaturedSudachiNode(candidate, keySudachiNode);
			if (replacedCandidate == null)
				continue;
			double score = (candidate.getFreq() * 0.4 + replacedCandidate.getFreq() * 0.6)
					* (1 + new Random().nextDouble());
			if (highScore < score) {
				replaced = replacedCandidate;
				original = candidate;
				highScore = score;
			}

		}
		if (original == null)
			return null;
		return new SimpleEntry<>(original, replaced);
	}

	private SudachiNodeKey getSudachiNodeKey(SudachiNode node) {
		Morpheme[] nodeMorphemes = node.getValue();
		Morpheme[] nodeKeyMorphemes = new Morpheme[nodeMorphemes.length - 1];
		for (int i = 0; i < nodeKeyMorphemes.length; i++) {
			nodeKeyMorphemes[i] = nodeMorphemes[i];
		}
		return new SudachiNodeKey(nodeKeyMorphemes);
	}

	private SudachiNode replaceFeaturedSudachiNode(SudachiNode node, SudachiNode keySudachiNode) {
		List<SudachiNode> candidates = null;
		if (keySudachiNode == null) {
			List<Entry<SudachiNodeKey, List<SudachiNode>>> allHeadList = getHeadList(featuredLanguageModel, 0);
			for (Entry<SudachiNodeKey, List<SudachiNode>> entry : allHeadList) {
				if (entry.getKey().equals(getSudachiNodeKey(node))) {
					candidates = entry.getValue();
					break;
				}
			}
		} else {
			candidates = featuredLanguageModel.findNextCandidates(keySudachiNode);
		}
		if (candidates == null)
			return null;

		Morpheme generalSudachiNode = node.getLastToken();
		List<String> generalSudachiNodeDetailPos = generalSudachiNode.partOfSpeech();
		String generalSudachiNodePos = generalSudachiNodeDetailPos.get(0);
		String generalSurface = generalSudachiNode.surface();

		Map<SudachiNode, Double> similarityMap = new HashMap<>(); //単語の類似度Map、candidate, value
		for (SudachiNode candidate : candidates) {
			if (candidate.isEOS())
				continue;
			Morpheme featuredSudachiNode = candidate.getLastToken();
			// 品詞が同一かつ自立語の形態素に限定
			List<String> featuredSudachiNodeDetailPos = featuredSudachiNode.partOfSpeech();
			String featuredSudachiNodePos = featuredSudachiNodeDetailPos.get(0);

			if (!featuredSudachiNodePos.equals(generalSudachiNodePos)
					|| featuredSudachiNodePos.contains("助詞")
					|| !featuredSudachiNodeDetailPos.get(5).equals(generalSudachiNodeDetailPos.get(5)))
				continue;

			String featuredSurface = featuredSudachiNode.surface();
			//Word2Vecで類似度を判定
			double cosineSimilarity = word2VecModel.similarity(generalSurface, featuredSurface);
			if (cosineSimilarity >= minCosineSim)
				similarityMap.put(candidate, cosineSimilarity);
		}

		if (similarityMap.size() < 1)
			return null;

		List<Entry<SudachiNode, Double>> entries = new ArrayList<>(similarityMap.entrySet());
		Collections.sort(entries, new Comparator<Entry<SudachiNode, Double>>() {
			@Override
			public int compare(Entry<SudachiNode, Double> o1, Entry<SudachiNode, Double> o2) {
				return o2.getValue().compareTo(o1.getValue());
			}
		});

		// 閾値以上のものからランダムに選択? or
		// 閾値以上のものからM個選択し、その中からランダムに選択? or <- とりあえずこれ(M=10)
		// 全体のNgram言語モデルに存在する形態素か判定?
		Random rnd = new Random();
		int index = entries.size() < 10 ? rnd.nextInt(entries.size()) : rnd.nextInt(10);
		SudachiNode selected = entries.get(index).getKey();
		return selected;
	}

}
