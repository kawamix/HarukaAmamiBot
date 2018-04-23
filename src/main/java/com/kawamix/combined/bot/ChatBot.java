package com.kawamix.combined.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;

import com.kawamix.sudachi.SudachiTokenizer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

public class ChatBot {

	public static void main(String[] args) {
		LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger rootLogger = loggerContext.getLogger("org.mongodb.driver");
		rootLogger.setLevel(Level.WARN);

		String rootDirectoryPathOfFeaturedTexts = "./resources/haruka.txt";
		String word2VecModelPath = "./data/word2vecModel_sudachi.txt";
		String allDocumentsPath = "./data/allDocuments.srl";
		String seq2VecPath = "./data/seq2vec.srl";
		String featuredLanguageModelPath = "./data/featuredLM_sudachi.srl";
		String stopWordsPath = "./resources/stopWords.txt";
		int n = 3;

		SudachiTokenizer sudachiTokenizer = new SudachiTokenizer();

		SudachiChatController chatController = new SudachiChatController(n, rootDirectoryPathOfFeaturedTexts,
				word2VecModelPath, allDocumentsPath, seq2VecPath,
				featuredLanguageModelPath, stopWordsPath);
		try {
			chatController.initialize(sudachiTokenizer);
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			String line;
			System.out.println("Input the text.");
			while (null != (line = br.readLine())) {
				//				String next = chatController.nextText(line);
				//				String next = chatController.characterizeText(line);
				List<String> nextList = chatController.nextTexts(line, 10);
				for (String next : nextList) {
					System.out.println("INPUT> " + line);
					//				System.out.println("OUTPUT> " + chatController.characterizeText(line));
					System.out.println("OUTPUT> " + next);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

}
