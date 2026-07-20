package com.quantumswap.app.api.read.model;

import java.util.Objects;

import com.google.gson.annotations.SerializedName;

/**
 * AccountTokenSummary mirrors the scan API token entry returned by
 * GET /account/{address}/tokens/{pageIndex}. Field names follow the same shape
 * used by the desktop wallet's listAccountTokens parsing.
 */
public class AccountTokenSummary {
  public static final String SERIALIZED_NAME_CONTRACT_ADDRESS = "contractAddress";
  @SerializedName(SERIALIZED_NAME_CONTRACT_ADDRESS)
  private String contractAddress;

  public static final String SERIALIZED_NAME_TOKEN_BALANCE = "tokenBalance";
  @SerializedName(SERIALIZED_NAME_TOKEN_BALANCE)
  private String tokenBalance;

  public static final String SERIALIZED_NAME_NAME = "name";
  @SerializedName(SERIALIZED_NAME_NAME)
  private String name;

  public static final String SERIALIZED_NAME_SYMBOL = "symbol";
  @SerializedName(SERIALIZED_NAME_SYMBOL)
  private String symbol;

  public static final String SERIALIZED_NAME_DECIMALS = "decimals";
  @SerializedName(SERIALIZED_NAME_DECIMALS)
  private Integer decimals;

  public String getContractAddress() { return contractAddress; }
  public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }

  public String getTokenBalance() { return tokenBalance; }
  public void setTokenBalance(String tokenBalance) { this.tokenBalance = tokenBalance; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getSymbol() { return symbol; }
  public void setSymbol(String symbol) { this.symbol = symbol; }

  public Integer getDecimals() { return decimals; }
  public void setDecimals(Integer decimals) { this.decimals = decimals; }

  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AccountTokenSummary other = (AccountTokenSummary) o;
    return Objects.equals(this.contractAddress, other.contractAddress) &&
        Objects.equals(this.tokenBalance, other.tokenBalance) &&
        Objects.equals(this.name, other.name) &&
        Objects.equals(this.symbol, other.symbol) &&
        Objects.equals(this.decimals, other.decimals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(contractAddress, tokenBalance, name, symbol, decimals);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccountTokenSummary {\n");
    sb.append("    contractAddress: ").append(contractAddress).append("\n");
    sb.append("    tokenBalance: ").append(tokenBalance).append("\n");
    sb.append("    name: ").append(name).append("\n");
    sb.append("    symbol: ").append(symbol).append("\n");
    sb.append("    decimals: ").append(decimals).append("\n");
    sb.append("}");
    return sb.toString();
  }
}
