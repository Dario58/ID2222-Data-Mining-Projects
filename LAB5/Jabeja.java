package se.kth.jabeja;

import org.apache.log4j.Logger;
import se.kth.jabeja.config.AnnealingPolicy;
import se.kth.jabeja.config.Config;
import se.kth.jabeja.config.NodeSelectionPolicy;
import se.kth.jabeja.io.FileIO;
import se.kth.jabeja.rand.RandNoGenerator;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Jabeja {
  final static Logger logger = Logger.getLogger(Jabeja.class);
  private final Config config;
  private final HashMap<Integer/*id*/, Node/*neighbors*/> entireGraph;
  private final List<Integer> nodeIds;
  private int numberOfSwaps;
  private int round;
  private float T;
  private float initialT;
  private boolean resultFileCreated = false;
  private Random random;

  //-------------------------------------------------------------------
  public Jabeja(HashMap<Integer, Node> graph, Config config) {
    this.entireGraph = graph;
    this.nodeIds = new ArrayList(entireGraph.keySet());
    this.round = 0;
    this.numberOfSwaps = 0;
    this.config = config;
    this.T = config.getTemperature();
    this.initialT = config.getTemperature();
    this.random = new Random(config.getSeed());
  }


  //-------------------------------------------------------------------
  public void startJabeja() throws IOException {
    for (round = 0; round < config.getRounds(); round++) {
      for (int id : entireGraph.keySet()) {
        sampleAndSwap(id);
      }

      //one cycle for all nodes have completed.
      //reduce the temperature
      saCoolDown(round);
      report();
    }
  }

  /**
   * Simulated analealing cooling function
   */
  private void saCoolDown(int round){
    float tMin;

    tMin = config.getAnnealingPolicy() == AnnealingPolicy.LINEAR ? 1.0f : 0.0001f;

    if (T > tMin && config.getAnnealingPolicy() == AnnealingPolicy.LINEAR) {
      T -= config.getDelta();
    } else if (T > tMin && !(config.getAnnealingPolicy() == AnnealingPolicy.LINEAR)) {
      T *= config.getDelta(); //EXPONENTIAL
    }
    else {
      T = tMin;
    }
    if(config.getRestart()>0 && (round % config.getRestart()) == 0) {
      T = initialT;
    }
  }

  /**
   * Sample and swap algorithm at node p
   * @param nodeId
   */
  private void sampleAndSwap(int nodeId) {
    Node partner = null;
    Node nodep = entireGraph.get(nodeId);

    if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
            || config.getNodeSelectionPolicy() == NodeSelectionPolicy.LOCAL) {
      // swap with random neighbors
      partner = findPartner(nodeId, getNeighbors(nodep));
    }

    if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
            || config.getNodeSelectionPolicy() == NodeSelectionPolicy.RANDOM) {
      // if local policy fails then randomly sample the entire graph
      if(partner == null) {
        partner = findPartner(nodeId, getSample(nodeId));
      }
    }

    // swap the colors
    if(partner != null) {
      int colorP = nodep.getColor();
      nodep.setColor(partner.getColor());
      partner.setColor(colorP);
      numberOfSwaps++;
    }
  }

  public Node findPartner(int nodeId, Integer[] nodes){

    Node nodep = entireGraph.get(nodeId);

    Node bestPartner = null;
    double highestBenefit = 0;

    if(config.getAnnealingPolicy() == AnnealingPolicy.LINEAR) {
      for(Integer n : nodes){
        Node nodeq = entireGraph.get(n);
        Float alpha = config.getAlpha();
        int dpp = getDegree(nodep, nodep.getColor());
        int dqq = getDegree(nodeq, nodeq.getColor());
        double old = Math.pow(dpp, alpha) + Math.pow(dqq,alpha);
        int dpq = getDegree(nodep, nodeq.getColor());
        int dqp = getDegree(nodeq, nodep.getColor());
        double new_val = Math.pow(dpq, alpha) + Math.pow(dqp,alpha);

        if(new_val * T > old && new_val > highestBenefit) {
          bestPartner = nodeq;
          highestBenefit = new_val;
        }
      }
      return bestPartner;
    }

    else {
      for(Integer n : nodes){
        Node nodeq = entireGraph.get(n);
        Float alpha = config.getAlpha();
        int dpp = getDegree(nodep, nodep.getColor());
        int dqq = getDegree(nodeq, nodeq.getColor());
        double old = Math.pow(dpp, alpha) + Math.pow(dqq,alpha);
        int dpq = getDegree(nodep, nodeq.getColor());
        int dqp = getDegree(nodeq, nodep.getColor());
        double new_val = Math.pow(dpq, alpha) + Math.pow(dqp,alpha);

        // acceptance probability: a_p = e^((new - old) / T)

        double acceptanceProb = Math.exp((new_val - old) / T);
        if(acceptanceProb > highestBenefit && (acceptanceProb > random.nextDouble() && new_val != old)){
          bestPartner = nodeq;
          highestBenefit = new_val;
        }
      }
      return bestPartner;
    }
  }

  /**
   * The the degreee on the node based on color
   * @param node
   * @param colorId
   * @return how many neighbors of the node have color == colorId
   */
  private int getDegree(Node node, int colorId){
    int degree = 0;
    for(int neighborId : node.getNeighbours()){
      Node neighbor = entireGraph.get(neighborId);
      if(neighbor.getColor() == colorId){
        degree++;
      }
    }
    return degree;
  }

  /**
   * Returns a uniformly random sample of the graph
   * @param currentNodeId
   * @return Returns a uniformly random sample of the graph
   */
  private Integer[] getSample(int currentNodeId) {
    int count = config.getUniformRandomSampleSize();
    int rndId;
    int size = entireGraph.size();
    ArrayList<Integer> rndIds = new ArrayList<Integer>();

    while (true) {
      rndId = nodeIds.get(RandNoGenerator.nextInt(size));
      if (rndId != currentNodeId && !rndIds.contains(rndId)) {
        rndIds.add(rndId);
        count--;
      }

      if (count == 0)
        break;
    }

    Integer[] ids = new Integer[rndIds.size()];
    return rndIds.toArray(ids);
  }

  /**
   * Get random neighbors. The number of random neighbors is controlled using
   * -closeByNeighbors command line argument which can be obtained from the config
   * using {@link Config#getRandomNeighborSampleSize()}
   * @param node
   * @return
   */
  private Integer[] getNeighbors(Node node) {
    ArrayList<Integer> list = node.getNeighbours();
    int count = config.getRandomNeighborSampleSize();
    int rndId;
    int index;
    int size = list.size();
    ArrayList<Integer> rndIds = new ArrayList<Integer>();

    if (size <= count)
      rndIds.addAll(list);
    else {
      while (true) {
        index = RandNoGenerator.nextInt(size);
        rndId = list.get(index);
        if (!rndIds.contains(rndId)) {
          rndIds.add(rndId);
          count--;
        }

        if (count == 0)
          break;
      }
    }

    Integer[] arr = new Integer[rndIds.size()];
    return rndIds.toArray(arr);
  }


  /**
   * Generate a report which is stored in a file in the output dir.
   *
   * @throws IOException
   */
  private void report() throws IOException {
    int grayLinks = 0;
    int migrations = 0; // number of nodes that have changed the initial color
    int size = entireGraph.size();

    for (int i : entireGraph.keySet()) {
      Node node = entireGraph.get(i);
      int nodeColor = node.getColor();
      ArrayList<Integer> nodeNeighbours = node.getNeighbours();

      if (nodeColor != node.getInitColor()) {
        migrations++;
      }

      if (nodeNeighbours != null) {
        for (int n : nodeNeighbours) {
          Node p = entireGraph.get(n);
          int pColor = p.getColor();

          if (nodeColor != pColor)
            grayLinks++;
        }
      }
    }

    int edgeCut = grayLinks / 2;

    logger.info("round: " + round +
            ", edge cut:" + edgeCut +
            ", swaps: " + numberOfSwaps +
            ", migrations: " + migrations);

    saveToFile(edgeCut, migrations);
  }

  private void saveToFile(int edgeCuts, int migrations) throws IOException {
    String delimiter = "\t\t";
    String outputFilePath = null;

    //output file name
    File inputFile = new File(config.getGraphFilePath());
    if(config.getAnnealingPolicy() == AnnealingPolicy.LINEAR) {
      outputFilePath = config.getOutputDir() +
              File.separator +
              inputFile.getName() + "_" +
              "NS" + "_" + config.getNodeSelectionPolicy() + "_" +
              "GICP" + "_" + config.getGraphInitialColorPolicy() + "_" +
              "T" + "_" + config.getTemperature() + "_" +
              "D" + "_" + config.getDelta() + "_" +
              "RNSS" + "_" + config.getRandomNeighborSampleSize() + "_" +
              "URSS" + "_" + config.getUniformRandomSampleSize() + "_" +
              "A" + "_" + config.getAlpha() + "_" +
              "R" + "_" + config.getRounds() + ".txt";
    }
    else if(config.getAnnealingPolicy() == AnnealingPolicy.EXPONENTIAL) {
      outputFilePath = config.getOutputDir() +
              File.separator +
              inputFile.getName() + "_" +
              "NS" + "_" + config.getNodeSelectionPolicy() + "_" +
              "GICP" + "_" + config.getGraphInitialColorPolicy() + "_" +
              "T" + "_" + config.getTemperature() + "_" +
              "D" + "_" + config.getDelta() + "_" +
              "RNSS" + "_" + config.getRandomNeighborSampleSize() + "_" +
              "URSS" + "_" + config.getUniformRandomSampleSize() + "_" +
              "A" + "_" + config.getAlpha() + "_" +
              "ANN" + "_" + config.getAnnealingPolicy() + "_" +
              "R" + "_" + config.getRounds() + ".txt";
    }

    if (!resultFileCreated) {
      File outputDir = new File(config.getOutputDir());
      if (!outputDir.exists()) {
        if (!outputDir.mkdir()) {
          throw new IOException("Unable to create the output directory");
        }
      }
      // create folder and result file with header
      String header = "# Migration is number of nodes that have changed color.";
      header += "\n\nRound" + delimiter + "Edge-Cut" + delimiter + "Swaps" + delimiter + "Migrations" + delimiter + "Skipped" + "\n";
      FileIO.write(header, outputFilePath);
      resultFileCreated = true;
    }

    FileIO.append(round + delimiter + (edgeCuts) + delimiter + numberOfSwaps + delimiter + migrations + "\n", outputFilePath);
  }
}
