package com.kawamix.combined.bot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;

import com.kawamix.combined.ngrammodel.forsudachi.NgramLanguageModel;
import com.kawamix.combined.ngrammodel.forsudachi.NgramSentenceGenerator;
import com.kawamix.combined.ngrammodel.forsudachi.NgramSentenceGenerator.GENERATEMODE;
import com.kawamix.combined.wmd.SimilarSentence;
import com.kawamix.sudachi.SudachiTokenizer;
import com.kawamix.word2vec.Word2VecModel;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.worksap.nlp.sudachi.Morpheme;

public class SudachiChatController {
	private int n = 2;
	private String rootDirectoryPathOfFeaturedTexts;
	private String word2VecModelPath;
	private String allDocumentsPath;
	private String seq2VecPath;
	private String featuredLanguageModelPath;
	private String stopWordsPath;

	private NgramSentenceGenerator sentenceGenerator;
	private NgramLanguageModel featuredLanguageModel;
	private SimilarSentence similarSentence;

	private MongoCollection<Document> collection;
	private SudachiTokenizer tokenizer;

	/**
	 *
	 * @param n N-gramのN
	 * @param rootDirectoryPathOfGeneralTexts 汎用テキストのルートディレクトリ
	 * @param rootDirectoryPathOfFeaturedTexts 特徴テキストのディレクトリ
	 */
	public SudachiChatController(int n, String rootDirectoryPathOfFeaturedTexts,
			String word2VecModelPath, String allDocumentsPath, String seq2VecPath, String featuredLanguageModelPath,
			String stopWordsPath) {
		this(rootDirectoryPathOfFeaturedTexts, word2VecModelPath);
		this.n = n;
		this.allDocumentsPath = allDocumentsPath;
		this.seq2VecPath = seq2VecPath;
		this.featuredLanguageModelPath = featuredLanguageModelPath;
		this.stopWordsPath = stopWordsPath;
	}

	public SudachiChatController(String rootDirectoryPathOfFeaturedTexts,
			String word2VecModelPath) {
		this.rootDirectoryPathOfFeaturedTexts = rootDirectoryPathOfFeaturedTexts;
		this.word2VecModelPath = word2VecModelPath;

	}

	public void initialize() throws IOException {
		SudachiTokenizer tokenizer = new SudachiTokenizer();
		initialize(tokenizer);
	}

	public void initialize(SudachiTokenizer tokenizer) throws IOException {
		loadLanguageModel(tokenizer);
		MongoClient client = new MongoClient("localhost", 27017);
		MongoDatabase database = client.getDatabase("twitter_reply");
		this.collection = database.getCollection("tweetpair");
	}

	private String getWakati(String text) {
		StringBuilder sb = new StringBuilder();
		for (Morpheme token : tokenizer.tokenize(text)) {
			sb.append(token.surface());
			sb.append(" ");
		}
		return sb.toString();
	}

	private void createSentenceGenerator() {
		Word2VecModel word2VecModel = new Word2VecModel(this.word2VecModelPath);
		sentenceGenerator = new NgramSentenceGenerator();
		sentenceGenerator.setN(this.n);
		sentenceGenerator.setTokenizer(this.tokenizer);
		sentenceGenerator.setWord2VecModel(word2VecModel);
		this.similarSentence = new SimilarSentence(word2VecModel, this.tokenizer, this.stopWordsPath);
		this.similarSentence.load(allDocumentsPath, seq2VecPath);
	}

	private void loadLanguageModel(SudachiTokenizer tokenizer) throws IOException {
		this.tokenizer = tokenizer;
		Path featuredDirPath = Paths.get(this.rootDirectoryPathOfFeaturedTexts);
		this.featuredLanguageModel = new NgramLanguageModel(n);
		this.featuredLanguageModel.setTokenizer(tokenizer);
		if (new File(featuredLanguageModelPath).exists()) {
			this.featuredLanguageModel.loadSerializedFile(featuredLanguageModelPath);
		} else {
			this.featuredLanguageModel.loadFile(featuredDirPath);
			this.featuredLanguageModel.save(featuredLanguageModelPath);
		}
		createSentenceGenerator();
		//		this.featuredLanguageModel.loadDirectory(featuredDirPath);
	}

	private List<String> getReplyTweetsFromId(List<Long> idList) {
		List<String> tweets = new ArrayList<>();
		for (long id : idList) {
			Document query = new Document();
			query.append("tweetid", id);
			MongoCursor<Document> cursor = this.collection.find(query).iterator();
			Document doc = cursor.next();
			if (doc == null)
				continue;
			boolean restriction = false;
			if (restriction) {
				Document user = (Document) doc.get("user");
				if (user == null)
					continue;
			}
			String tweet = doc.getString("tweet");
			tweets.add(tweet.replaceAll("^ ", ""));
		}
		return tweets;
	}

	public String characterizeText(String original) {
		// リプライ文の集合よりN-gram言語モデル生成
		List<String> lines = new ArrayList<>();
		lines.add(original);
		NgramLanguageModel generalLanguageModel = new NgramLanguageModel(n);
		generalLanguageModel.setTokenizer(this.tokenizer);
		generalLanguageModel.loadLines(lines);

		// リプライ言語モデルと特徴的言語モデルよりテキスト生成
		sentenceGenerator.setMode(GENERATEMODE.CHARACTERIZE);
		return sentenceGenerator.generateText(featuredLanguageModel, generalLanguageModel);
	}

	private NgramLanguageModel generateLM(String text) {
		// 類似文集合取得
		List<Long> similarSentenceIdList = similarSentence.getSimilarSentenceIdList(getWakati(text),
				SimilarSentence.MAX_WMD_VALUE);

		// 各類似文に対応するリプライ文を取得
		List<String> tweets = getReplyTweetsFromId(similarSentenceIdList);
		for (String tweet : tweets) {
			System.out.println("similar:" + tweet);
		}
		System.out.println("found similar text.");
		if (tweets.size() < 1)
			return null;

		// リプライ文の集合よりN-gram言語モデル生成
		NgramLanguageModel generalLanguageModel = new NgramLanguageModel(n);
		generalLanguageModel.setTokenizer(this.tokenizer);
		generalLanguageModel.loadLines(tweets);
		return generalLanguageModel;
	}

	public List<String> nextTexts(String text, int num) {
		NgramLanguageModel generalLanguageModel = generateLM(text);
		if (generalLanguageModel == null)
			return new ArrayList<>();

		List<String> texts = new ArrayList<>();
		for (int i = 0; i < num; i++) {
			sentenceGenerator.setMode(GENERATEMODE.CHAT);
			String next = sentenceGenerator.generateText(featuredLanguageModel, generalLanguageModel);
			texts.add(next);
		}
		return texts;
	}

	public String nextText(String text) {
		// リプライ言語モデルと特徴的言語モデルよりテキスト生成
		NgramLanguageModel generalLanguageModel = generateLM(text);
		if (generalLanguageModel == null)
			return null;
		sentenceGenerator.setMode(GENERATEMODE.CHAT);
		return sentenceGenerator.generateText(featuredLanguageModel, generalLanguageModel);

	}
}
