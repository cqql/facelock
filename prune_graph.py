#!/usr/bin/env python

"""Prune an InceptionV1 graph.

This should actually prune all nodes from the graph that are not strictly
necessary to compute the embeddings. However, my first attempt produced an
broken model, so right now this only removes the parent nodes of `input` because
the android version of tensorflow does not have FIFOQueue ops.

"""

import argparse
import copy
from collections import deque

from tensorflow.core.framework import graph_pb2

def node_name(name):
    if name.startswith("^"):
        return name[1:]
    else:
        return name.split(":")[0]


def bfs(edges, start):
    """Return all nodes reachable from start."""
    reachable = set()
    remaining = deque([start])

    while remaining:
        next = remaining.popleft()

        if next in reachable:
            continue

        reachable.add(next)
        remaining.extend(edges[next])


    return reachable


def replace_node(graph_def, name, fun):
    g = tf.Graph()
    with g.as_default():
        fun()

    old_node = [n for n in graph_def.node if n.name == name][0]
    new_node = copy.deepcopy(g.as_graph_def().node[0])
    graph_def.node.remove(old_node)
    graph_def.node.extend([new_node])


def main():
    parser = argparse.ArgumentParser(
        description="Prune unnecessary nodes from the graph")
    parser.add_argument("pb", help="Protobuf graph to prune")
    parser.add_argument("out", help="Output path")
    args = parser.parse_args()

    pb_path = args.pb
    out_path = args.out

    graph = graph_pb2.GraphDef()
    with tf.gfile.GFile(pb_path, "rb") as f:
        graph.ParseFromString(f.read())

    # Find input and embeddings nodes
    nodes = graph.node
    by_name = {n.name: n for n in nodes}
    input = by_name["input"]
    embeddings = by_name["embeddings"]
    phase_train = by_name["phase_train"]

    backward_edges = {n.name: [node_name(n2) for n2 in n.input] for n in nodes}
    to_embeddings = bfs(backward_edges, "embeddings")
    inverse_edges = {n.name: set() for n in nodes}
    for k, v in backward_edges.items():
        for n in v:
            inverse_edges[n].add(k)
    to_input = bfs(backward_edges, "input") - set(["input"])

    # All nodes that are "between" input and embeddings
    #between = from_input & to_embeddings

    out_graph = graph_pb2.GraphDef()
    for n in nodes:
        if not n.name in to_input:
            copied = copy.deepcopy(n)

            # Remove edges to removed nodes
            for name in to_input:
                if name in copied.input:
                    copied.input.remove(name)

            out_graph.node.extend([copied])
    out_graph.versions.CopyFrom(graph.versions)
    out_graph.library.CopyFrom(graph.library)

    # Replace the input identity node with a placeholder
    replace_node(out_graph, "input", lambda: tf.placeholder(tf.float32, shape=(None, 160, 160, 3), name="input"))
    replace_node(out_graph, "phase_train", lambda: tf.constant(False, tf.bool, name="phase_train"))

    with tf.gfile.GFile(out_path, "wb") as f:
        f.write(out_graph.SerializeToString())


if __name__ == "__main__":
    main()
