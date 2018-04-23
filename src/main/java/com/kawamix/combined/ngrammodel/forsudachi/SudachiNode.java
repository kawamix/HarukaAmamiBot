package com.kawamix.combined.ngrammodel.forsudachi;

import java.io.Serializable;

import com.worksap.nlp.sudachi.Morpheme;

public class SudachiNode implements Serializable {
	private Morpheme[] tokens;
	private int freq;

	public SudachiNode(Morpheme[] value) {
		this.tokens = value;
		this.freq = 1;
	}

	public Morpheme[] getValue() {
		return this.tokens;
	}

	public Morpheme getLastToken() {
		return this.tokens[this.tokens.length - 1];
	}

	public String[] getString() {
		if (this.tokens == null)
			return null;
		String[] str = new String[this.tokens.length];
		for (int i = 0; i < str.length; i++) {
			str[i] = this.tokens[i] == null ? null : this.tokens[i].surface();
		}
		return str;
	}

	public void addFreq() {
		freq++;
	}

	public int getFreq() {
		return this.freq;
	}

	public boolean isEOS() {
		return this.tokens[this.tokens.length - 1] == null;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (getClass() != obj.getClass())
			return false;
		SudachiNode other = (SudachiNode) obj;
		Morpheme[] otherTokens = other.tokens;
		if (this.tokens == null || otherTokens == null)
			return false;
		if (this.tokens.length != otherTokens.length)
			return false;
		for (int i = 0; i < this.tokens.length; i++) {
			Morpheme token1 = this.tokens[i];
			Morpheme token2 = otherTokens[i];
			if (token1 == null || token2 == null) {
				if (token1 == null && token2 == null) {
					continue;
				} else {
					return false;
				}
			}
			if (!token1.surface().equals(token2.surface()))
				return false;
			if (!token1.partOfSpeech().equals(token2.partOfSpeech()))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (isEOS() ? "null".hashCode() : tokens[tokens.length - 1].surface().hashCode());
		result = prime * result + toString().hashCode();
		return result;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[");
		for (Morpheme token : this.tokens) {
			sb.append(token == null ? "null" : token.surface());
			sb.append(",");
		}
		if (sb.length() > 1)
			sb.deleteCharAt(sb.length() - 1);
		sb.append("]");
		return sb.toString();
	}

}
