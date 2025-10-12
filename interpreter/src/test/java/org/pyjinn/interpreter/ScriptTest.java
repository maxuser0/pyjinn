// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.pyjinn.parser.PyjinnParser;

public class ScriptTest {
  /* Generated from Python code:

      def times_two(x):
        y = x * 2
        return y
  */
  private static final String timesTwoJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "times_two",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [
                {
                  "type": "arg",
                  "arg": "x",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 14
                }
              ],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "y",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 6
                  },
                  "op": {
                    "type": "Mult"
                  },
                  "right": {
                    "type": "Constant",
                    "value": 2,
                    "lineno": 2,
                    "col_offset": 10,
                    "typename": "int"
                  },
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "y",
                  "lineno": 3,
                  "col_offset": 9
                },
                "lineno": 3,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def populate_array(array, index, value):
        array[index] = value
        return array
  */
  private static final String populateArrayJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "populate_array",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [
                {
                  "type": "arg",
                  "arg": "array",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 19
                },
                {
                  "type": "arg",
                  "arg": "index",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 26
                },
                {
                  "type": "arg",
                  "arg": "value",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 33
                }
              ],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Subscript",
                    "value": {
                      "type": "Name",
                      "id": "array",
                      "lineno": 2,
                      "col_offset": 2
                    },
                    "slice": {
                      "type": "Name",
                      "id": "index",
                      "lineno": 2,
                      "col_offset": 8
                    },
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Name",
                  "id": "value",
                  "lineno": 2,
                  "col_offset": 17
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "array",
                  "lineno": 3,
                  "col_offset": 9
                },
                "lineno": 3,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def str_int_func(x, y):
        return str(float(x + str(y)))

      def type_conversions():
        return str(bool(0.0)) + str_int_func("2.", 3)
  */
  private static final String typeConversionsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "str_int_func",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [
                {
                  "type": "arg",
                  "arg": "x",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 17
                },
                {
                  "type": "arg",
                  "arg": "y",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 20
                }
              ],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Return",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "str",
                    "lineno": 2,
                    "col_offset": 9
                  },
                  "args": [
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "float",
                        "lineno": 2,
                        "col_offset": 13
                      },
                      "args": [
                        {
                          "type": "BinOp",
                          "left": {
                            "type": "Name",
                            "id": "x",
                            "lineno": 2,
                            "col_offset": 19
                          },
                          "op": {
                            "type": "Add"
                          },
                          "right": {
                            "type": "Call",
                            "func": {
                              "type": "Name",
                              "id": "str",
                              "lineno": 2,
                              "col_offset": 23
                            },
                            "args": [
                              {
                                "type": "Name",
                                "id": "y",
                                "lineno": 2,
                                "col_offset": 27
                              }
                            ],
                            "keywords": [],
                            "lineno": 2,
                            "col_offset": 23
                          },
                          "lineno": 2,
                          "col_offset": 19
                        }
                      ],
                      "keywords": [],
                      "lineno": 2,
                      "col_offset": 13
                    }
                  ],
                  "keywords": [],
                  "lineno": 2,
                  "col_offset": 9
                },
                "lineno": 2,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "type_conversions",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Return",
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "Call",
                    "func": {
                      "type": "Name",
                      "id": "str",
                      "lineno": 5,
                      "col_offset": 9
                    },
                    "args": [
                      {
                        "type": "Call",
                        "func": {
                          "type": "Name",
                          "id": "bool",
                          "lineno": 5,
                          "col_offset": 13
                        },
                        "args": [
                          {
                            "type": "Constant",
                            "value": 0.0,
                            "lineno": 5,
                            "col_offset": 18,
                            "typename": "float"
                          }
                        ],
                        "keywords": [],
                        "lineno": 5,
                        "col_offset": 13
                      }
                    ],
                    "keywords": [],
                    "lineno": 5,
                    "col_offset": 9
                  },
                  "op": {
                    "type": "Add"
                  },
                  "right": {
                    "type": "Call",
                    "func": {
                      "type": "Name",
                      "id": "str_int_func",
                      "lineno": 5,
                      "col_offset": 26
                    },
                    "args": [
                      {
                        "type": "Constant",
                        "value": "2.",
                        "lineno": 5,
                        "col_offset": 39,
                        "typename": "str"
                      },
                      {
                        "type": "Constant",
                        "value": 3,
                        "lineno": 5,
                        "col_offset": 45,
                        "typename": "int"
                      }
                    ],
                    "keywords": [],
                    "lineno": 5,
                    "col_offset": 26
                  },
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 4,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      x = 0

      def add_one():
        global x
        x = x + 1

      def increment_global():
        add_one()
        add_one()
        return x
  */
  private static final String incrementGlobalJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "Assign",
            "targets": [
              {
                "type": "Name",
                "id": "x",
                "lineno": 1,
                "col_offset": 0
              }
            ],
            "value": {
              "type": "Constant",
              "value": 0,
              "lineno": 1,
              "col_offset": 4,
              "typename": "int"
            },
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "add_one",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Global",
                "names": [
                  "x"
                ],
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 5,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "Name",
                    "id": "x",
                    "lineno": 5,
                    "col_offset": 6
                  },
                  "op": {
                    "type": "Add"
                  },
                  "right": {
                    "type": "Constant",
                    "value": 1,
                    "lineno": 5,
                    "col_offset": 10,
                    "typename": "int"
                  },
                  "lineno": 5,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 3,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "increment_global",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "add_one",
                    "lineno": 8,
                    "col_offset": 2
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 8,
                  "col_offset": 2
                },
                "lineno": 8,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "add_one",
                    "lineno": 9,
                    "col_offset": 2
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 9,
                  "col_offset": 2
                },
                "lineno": 9,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 10,
                  "col_offset": 9
                },
                "lineno": 10,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 7,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def factorial(n):
        if n:
          return n * factorial(n - 1)
        return 1
  */
  private static final String factorialJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "factorial",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [
                {
                  "type": "arg",
                  "arg": "n",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 1,
                  "col_offset": 14
                }
              ],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "If",
                "test": {
                  "type": "Name",
                  "id": "n",
                  "lineno": 2,
                  "col_offset": 5
                },
                "body": [
                  {
                    "type": "Return",
                    "value": {
                      "type": "BinOp",
                      "left": {
                        "type": "Name",
                        "id": "n",
                        "lineno": 3,
                        "col_offset": 11
                      },
                      "op": {
                        "type": "Mult"
                      },
                      "right": {
                        "type": "Call",
                        "func": {
                          "type": "Name",
                          "id": "factorial",
                          "lineno": 3,
                          "col_offset": 15
                        },
                        "args": [
                          {
                            "type": "BinOp",
                            "left": {
                              "type": "Name",
                              "id": "n",
                              "lineno": 3,
                              "col_offset": 25
                            },
                            "op": {
                              "type": "Sub"
                            },
                            "right": {
                              "type": "Constant",
                              "value": 1,
                              "lineno": 3,
                              "col_offset": 29,
                              "typename": "int"
                            },
                            "lineno": 3,
                            "col_offset": 25
                          }
                        ],
                        "keywords": [],
                        "lineno": 3,
                        "col_offset": 15
                      },
                      "lineno": 3,
                      "col_offset": 11
                    },
                    "lineno": 3,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Constant",
                  "value": 1,
                  "lineno": 4,
                  "col_offset": 9,
                  "typename": "int"
                },
                "lineno": 4,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def sqrt9():
        Math = JavaClass("java.lang.Math")
        return Math.sqrt(9)
  */
  private static final String sqrt9JsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "sqrt9",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "Math",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "JavaClass",
                    "lineno": 2,
                    "col_offset": 9
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "java.lang.Math",
                      "lineno": 2,
                      "col_offset": 19,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 2,
                  "col_offset": 9
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "Math",
                      "lineno": 3,
                      "col_offset": 9
                    },
                    "attr": "sqrt",
                    "lineno": 3,
                    "col_offset": 9
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 9,
                      "lineno": 3,
                      "col_offset": 19,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 9
                },
                "lineno": 3,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def nested_func_vars():
        x = "x"
        def bar():
          y = "y"
          def baz():
            z = "z"
            return "baz(" + x + y + z + ")"
          return baz() + ", bar(" + x + y + ")"
        return bar() + ", foo(" + x + ")"
  */
  private static final String nestedFuncVarsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "nested_func_vars",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Constant",
                  "value": "x",
                  "lineno": 2,
                  "col_offset": 6,
                  "typename": "str"
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "FunctionDef",
                "name": "bar",
                "args": {
                  "type": "arguments",
                  "posonlyargs": [],
                  "args": [],
                  "vararg": null,
                  "kwonlyargs": [],
                  "kw_defaults": [],
                  "kwarg": null,
                  "defaults": []
                },
                "body": [
                  {
                    "type": "Assign",
                    "targets": [
                      {
                        "type": "Name",
                        "id": "y",
                        "lineno": 4,
                        "col_offset": 4
                      }
                    ],
                    "value": {
                      "type": "Constant",
                      "value": "y",
                      "lineno": 4,
                      "col_offset": 8,
                      "typename": "str"
                    },
                    "type_comment": null,
                    "lineno": 4,
                    "col_offset": 4
                  },
                  {
                    "type": "FunctionDef",
                    "name": "baz",
                    "args": {
                      "type": "arguments",
                      "posonlyargs": [],
                      "args": [],
                      "vararg": null,
                      "kwonlyargs": [],
                      "kw_defaults": [],
                      "kwarg": null,
                      "defaults": []
                    },
                    "body": [
                      {
                        "type": "Assign",
                        "targets": [
                          {
                            "type": "Name",
                            "id": "z",
                            "lineno": 6,
                            "col_offset": 6
                          }
                        ],
                        "value": {
                          "type": "Constant",
                          "value": "z",
                          "lineno": 6,
                          "col_offset": 10,
                          "typename": "str"
                        },
                        "type_comment": null,
                        "lineno": 6,
                        "col_offset": 6
                      },
                      {
                        "type": "Return",
                        "value": {
                          "type": "BinOp",
                          "left": {
                            "type": "BinOp",
                            "left": {
                              "type": "BinOp",
                              "left": {
                                "type": "BinOp",
                                "left": {
                                  "type": "Constant",
                                  "value": "baz(",
                                  "lineno": 7,
                                  "col_offset": 13,
                                  "typename": "str"
                                },
                                "op": {
                                  "type": "Add"
                                },
                                "right": {
                                  "type": "Name",
                                  "id": "x",
                                  "lineno": 7,
                                  "col_offset": 22
                                },
                                "lineno": 7,
                                "col_offset": 13
                              },
                              "op": {
                                "type": "Add"
                              },
                              "right": {
                                "type": "Name",
                                "id": "y",
                                "lineno": 7,
                                "col_offset": 26
                              },
                              "lineno": 7,
                              "col_offset": 13
                            },
                            "op": {
                              "type": "Add"
                            },
                            "right": {
                              "type": "Name",
                              "id": "z",
                              "lineno": 7,
                              "col_offset": 30
                            },
                            "lineno": 7,
                            "col_offset": 13
                          },
                          "op": {
                            "type": "Add"
                          },
                          "right": {
                            "type": "Constant",
                            "value": ")",
                            "lineno": 7,
                            "col_offset": 34,
                            "typename": "str"
                          },
                          "lineno": 7,
                          "col_offset": 13
                        },
                        "lineno": 7,
                        "col_offset": 6
                      }
                    ],
                    "decorator_list": [],
                    "returns": null,
                    "type_comment": null,
                    "lineno": 5,
                    "col_offset": 4
                  },
                  {
                    "type": "Return",
                    "value": {
                      "type": "BinOp",
                      "left": {
                        "type": "BinOp",
                        "left": {
                          "type": "BinOp",
                          "left": {
                            "type": "BinOp",
                            "left": {
                              "type": "Call",
                              "func": {
                                "type": "Name",
                                "id": "baz",
                                "lineno": 8,
                                "col_offset": 11
                              },
                              "args": [],
                              "keywords": [],
                              "lineno": 8,
                              "col_offset": 11
                            },
                            "op": {
                              "type": "Add"
                            },
                            "right": {
                              "type": "Constant",
                              "value": ", bar(",
                              "lineno": 8,
                              "col_offset": 19,
                              "typename": "str"
                            },
                            "lineno": 8,
                            "col_offset": 11
                          },
                          "op": {
                            "type": "Add"
                          },
                          "right": {
                            "type": "Name",
                            "id": "x",
                            "lineno": 8,
                            "col_offset": 30
                          },
                          "lineno": 8,
                          "col_offset": 11
                        },
                        "op": {
                          "type": "Add"
                        },
                        "right": {
                          "type": "Name",
                          "id": "y",
                          "lineno": 8,
                          "col_offset": 34
                        },
                        "lineno": 8,
                        "col_offset": 11
                      },
                      "op": {
                        "type": "Add"
                      },
                      "right": {
                        "type": "Constant",
                        "value": ")",
                        "lineno": 8,
                        "col_offset": 38,
                        "typename": "str"
                      },
                      "lineno": 8,
                      "col_offset": 11
                    },
                    "lineno": 8,
                    "col_offset": 4
                  }
                ],
                "decorator_list": [],
                "returns": null,
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "BinOp",
                    "left": {
                      "type": "BinOp",
                      "left": {
                        "type": "Call",
                        "func": {
                          "type": "Name",
                          "id": "bar",
                          "lineno": 9,
                          "col_offset": 9
                        },
                        "args": [],
                        "keywords": [],
                        "lineno": 9,
                        "col_offset": 9
                      },
                      "op": {
                        "type": "Add"
                      },
                      "right": {
                        "type": "Constant",
                        "value": ", foo(",
                        "lineno": 9,
                        "col_offset": 17,
                        "typename": "str"
                      },
                      "lineno": 9,
                      "col_offset": 9
                    },
                    "op": {
                      "type": "Add"
                    },
                    "right": {
                      "type": "Name",
                      "id": "x",
                      "lineno": 9,
                      "col_offset": 28
                    },
                    "lineno": 9,
                    "col_offset": 9
                  },
                  "op": {
                    "type": "Add"
                  },
                  "right": {
                    "type": "Constant",
                    "value": ")",
                    "lineno": 9,
                    "col_offset": 32,
                    "typename": "str"
                  },
                  "lineno": 9,
                  "col_offset": 9
                },
                "lineno": 9,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def call_sibling_nested_func():
        def bar():
          return "bar"
        def baz():
          return bar()
        return baz()
  */
  private static final String callSiblingNestedFuncJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "call_sibling_nested_func",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "FunctionDef",
                "name": "bar",
                "args": {
                  "type": "arguments",
                  "posonlyargs": [],
                  "args": [],
                  "vararg": null,
                  "kwonlyargs": [],
                  "kw_defaults": [],
                  "kwarg": null,
                  "defaults": []
                },
                "body": [
                  {
                    "type": "Return",
                    "value": {
                      "type": "Constant",
                      "value": "bar",
                      "lineno": 3,
                      "col_offset": 11,
                      "typename": "str"
                    },
                    "lineno": 3,
                    "col_offset": 4
                  }
                ],
                "decorator_list": [],
                "returns": null,
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "FunctionDef",
                "name": "baz",
                "args": {
                  "type": "arguments",
                  "posonlyargs": [],
                  "args": [],
                  "vararg": null,
                  "kwonlyargs": [],
                  "kw_defaults": [],
                  "kwarg": null,
                  "defaults": []
                },
                "body": [
                  {
                    "type": "Return",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "bar",
                        "lineno": 5,
                        "col_offset": 11
                      },
                      "args": [],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 11
                    },
                    "lineno": 5,
                    "col_offset": 4
                  }
                ],
                "decorator_list": [],
                "returns": null,
                "type_comment": null,
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "baz",
                    "lineno": 6,
                    "col_offset": 9
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 6,
                  "col_offset": 9
                },
                "lineno": 6,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def list_ops():
        x = [1, 2, 3]
        x[0] += 100
        x += ["bar"]
        return x
  */
  private static final String listOpsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "list_ops",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "lineno": 2,
                      "col_offset": 7,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "lineno": 2,
                      "col_offset": 10,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 3,
                      "lineno": 2,
                      "col_offset": 13,
                      "typename": "int"
                    }
                  ],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "AugAssign",
                "target": {
                  "type": "Subscript",
                  "value": {
                    "type": "Name",
                    "id": "x",
                    "lineno": 3,
                    "col_offset": 2
                  },
                  "slice": {
                    "type": "Constant",
                    "value": 0,
                    "lineno": 3,
                    "col_offset": 4,
                    "typename": "int"
                  },
                  "lineno": 3,
                  "col_offset": 2
                },
                "op": {
                  "type": "Add"
                },
                "value": {
                  "type": "Constant",
                  "value": 100,
                  "lineno": 3,
                  "col_offset": 10,
                  "typename": "int"
                },
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "AugAssign",
                "target": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 4,
                  "col_offset": 2
                },
                "op": {
                  "type": "Add"
                },
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Constant",
                      "value": "bar",
                      "lineno": 4,
                      "col_offset": 8,
                      "typename": "str"
                    }
                  ],
                  "lineno": 4,
                  "col_offset": 7
                },
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def ctor_and_method_overloads():
        StringBuilder = JavaClass("java.lang.StringBuilder")
        builder = StringBuilder("This")
        builder.append(" is ")
        builder.append(1)
        builder.append(" test.")
        return builder.toString()
  */
  private static final String ctorAndMethodOverloadsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "ctor_and_method_overloads",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "StringBuilder",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "JavaClass",
                    "lineno": 2,
                    "col_offset": 18
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "java.lang.StringBuilder",
                      "lineno": 2,
                      "col_offset": 28,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 2,
                  "col_offset": 18
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "builder",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "StringBuilder",
                    "lineno": 3,
                    "col_offset": 12
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "This",
                      "lineno": 3,
                      "col_offset": 26,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 12
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "builder",
                      "lineno": 4,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 4,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": " is ",
                      "lineno": 4,
                      "col_offset": 17,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 4,
                  "col_offset": 2
                },
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "builder",
                      "lineno": 5,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 5,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "lineno": 5,
                      "col_offset": 17,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 5,
                  "col_offset": 2
                },
                "lineno": 5,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "builder",
                      "lineno": 6,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 6,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": " test.",
                      "lineno": 6,
                      "col_offset": 17,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 6,
                  "col_offset": 2
                },
                "lineno": 6,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "builder",
                      "lineno": 7,
                      "col_offset": 9
                    },
                    "attr": "toString",
                    "lineno": 7,
                    "col_offset": 9
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 7,
                  "col_offset": 9
                },
                "lineno": 7,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def range_stop():
        x = []
        for i in range(3):
          x.append(i)
        return x
  */
  private static final String rangeStopJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "range_stop",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "For",
                "target": {
                  "type": "Name",
                  "id": "i",
                  "lineno": 3,
                  "col_offset": 6
                },
                "iter": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "range",
                    "lineno": 3,
                    "col_offset": 11
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 3,
                      "lineno": 3,
                      "col_offset": 17,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 11
                },
                "body": [
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "x",
                          "lineno": 4,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 4,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "i",
                          "lineno": 4,
                          "col_offset": 13
                        }
                      ],
                      "keywords": [],
                      "lineno": 4,
                      "col_offset": 4
                    },
                    "lineno": 4,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def range_start_stop_step():
        x = []
        for i in range(4, 10, 2):
          x.append(i)
        return x
  */
  private static final String rangeStartStopStepJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "range_start_stop_step",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "For",
                "target": {
                  "type": "Name",
                  "id": "i",
                  "lineno": 3,
                  "col_offset": 6
                },
                "iter": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "range",
                    "lineno": 3,
                    "col_offset": 11
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 4,
                      "lineno": 3,
                      "col_offset": 17,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 10,
                      "lineno": 3,
                      "col_offset": 20,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "lineno": 3,
                      "col_offset": 24,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 11
                },
                "body": [
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "x",
                          "lineno": 4,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 4,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "i",
                          "lineno": 4,
                          "col_offset": 13
                        }
                      ],
                      "keywords": [],
                      "lineno": 4,
                      "col_offset": 4
                    },
                    "lineno": 4,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def break_for_loop():
        x = []
        for i in range(10):
          if i >= 2:
            break
          x.append(i)
        return x
  */
  private static final String breakForLoopJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "break_for_loop",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "For",
                "target": {
                  "type": "Name",
                  "id": "i",
                  "lineno": 3,
                  "col_offset": 6
                },
                "iter": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "range",
                    "lineno": 3,
                    "col_offset": 11
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 10,
                      "lineno": 3,
                      "col_offset": 17,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 11
                },
                "body": [
                  {
                    "type": "If",
                    "test": {
                      "type": "Compare",
                      "left": {
                        "type": "Name",
                        "id": "i",
                        "lineno": 4,
                        "col_offset": 7
                      },
                      "ops": [
                        {
                          "type": "GtE"
                        }
                      ],
                      "comparators": [
                        {
                          "type": "Constant",
                          "value": 2,
                          "lineno": 4,
                          "col_offset": 12,
                          "typename": "int"
                        }
                      ],
                      "lineno": 4,
                      "col_offset": 7
                    },
                    "body": [
                      {
                        "type": "Break",
                        "lineno": 5,
                        "col_offset": 6
                      }
                    ],
                    "orelse": [],
                    "lineno": 4,
                    "col_offset": 4
                  },
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "x",
                          "lineno": 6,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 6,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "i",
                          "lineno": 6,
                          "col_offset": 13
                        }
                      ],
                      "keywords": [],
                      "lineno": 6,
                      "col_offset": 4
                    },
                    "lineno": 6,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 7,
                  "col_offset": 9
                },
                "lineno": 7,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def numeric_types():
        def t(x):
          return type(type(x)).getSimpleName()

        return [t(123), t(91234567890), t(123.), t(3.14159265359)]
  */
  private static final String numericTypesJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "numeric_types",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "FunctionDef",
                "name": "t",
                "args": {
                  "type": "arguments",
                  "posonlyargs": [],
                  "args": [
                    {
                      "type": "arg",
                      "arg": "x",
                      "annotation": null,
                      "type_comment": null,
                      "lineno": 2,
                      "col_offset": 8
                    }
                  ],
                  "vararg": null,
                  "kwonlyargs": [],
                  "kw_defaults": [],
                  "kwarg": null,
                  "defaults": []
                },
                "body": [
                  {
                    "type": "Return",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Call",
                          "func": {
                            "type": "Name",
                            "id": "type",
                            "lineno": 3,
                            "col_offset": 11
                          },
                          "args": [
                            {
                              "type": "Call",
                              "func": {
                                "type": "Name",
                                "id": "type",
                                "lineno": 3,
                                "col_offset": 16
                              },
                              "args": [
                                {
                                  "type": "Name",
                                  "id": "x",
                                  "lineno": 3,
                                  "col_offset": 21
                                }
                              ],
                              "keywords": [],
                              "lineno": 3,
                              "col_offset": 16
                            }
                          ],
                          "keywords": [],
                          "lineno": 3,
                          "col_offset": 11
                        },
                        "attr": "getSimpleName",
                        "lineno": 3,
                        "col_offset": 11
                      },
                      "args": [],
                      "keywords": [],
                      "lineno": 3,
                      "col_offset": 11
                    },
                    "lineno": 3,
                    "col_offset": 4
                  }
                ],
                "decorator_list": [],
                "returns": null,
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "t",
                        "lineno": 5,
                        "col_offset": 10
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": 123,
                          "lineno": 5,
                          "col_offset": 12,
                          "typename": "int"
                        }
                      ],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 10
                    },
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "t",
                        "lineno": 5,
                        "col_offset": 18
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": 91234567890,
                          "lineno": 5,
                          "col_offset": 20,
                          "typename": "int"
                        }
                      ],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 18
                    },
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "t",
                        "lineno": 5,
                        "col_offset": 34
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": 123.0,
                          "lineno": 5,
                          "col_offset": 36,
                          "typename": "float"
                        }
                      ],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 34
                    },
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "t",
                        "lineno": 5,
                        "col_offset": 43
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": 3.14159265359,
                          "lineno": 5,
                          "col_offset": 45,
                          "typename": "float"
                        }
                      ],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 43
                    }
                  ],
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def while_loop():
        a = []
        n = 0
        while n < 3:
          a.append(n)
          n += 1
        return a
  */
  private static final String whileLoopJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "while_loop",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "a",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "n",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Constant",
                  "value": 0,
                  "lineno": 3,
                  "col_offset": 6,
                  "typename": "int"
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "While",
                "test": {
                  "type": "Compare",
                  "left": {
                    "type": "Name",
                    "id": "n",
                    "lineno": 4,
                    "col_offset": 8
                  },
                  "ops": [
                    {
                      "type": "Lt"
                    }
                  ],
                  "comparators": [
                    {
                      "type": "Constant",
                      "value": 3,
                      "lineno": 4,
                      "col_offset": 12,
                      "typename": "int"
                    }
                  ],
                  "lineno": 4,
                  "col_offset": 8
                },
                "body": [
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "a",
                          "lineno": 5,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 5,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "n",
                          "lineno": 5,
                          "col_offset": 13
                        }
                      ],
                      "keywords": [],
                      "lineno": 5,
                      "col_offset": 4
                    },
                    "lineno": 5,
                    "col_offset": 4
                  },
                  {
                    "type": "AugAssign",
                    "target": {
                      "type": "Name",
                      "id": "n",
                      "lineno": 6,
                      "col_offset": 4
                    },
                    "op": {
                      "type": "Add"
                    },
                    "value": {
                      "type": "Constant",
                      "value": 1,
                      "lineno": 6,
                      "col_offset": 9,
                      "typename": "int"
                    },
                    "lineno": 6,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "a",
                  "lineno": 7,
                  "col_offset": 9
                },
                "lineno": 7,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def break_while_loop():
        a = []
        while True:
          a.append(1)
          break
        return a
  */
  private static final String breakWhileLoopJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "break_while_loop",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "a",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "While",
                "test": {
                  "type": "Constant",
                  "value": true,
                  "lineno": 3,
                  "col_offset": 8,
                  "typename": "bool"
                },
                "body": [
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "a",
                          "lineno": 4,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 4,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": 1,
                          "lineno": 4,
                          "col_offset": 13,
                          "typename": "int"
                        }
                      ],
                      "keywords": [],
                      "lineno": 4,
                      "col_offset": 4
                    },
                    "lineno": 4,
                    "col_offset": 4
                  },
                  {
                    "type": "Break",
                    "lineno": 5,
                    "col_offset": 4
                  }
                ],
                "orelse": [],
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "a",
                  "lineno": 6,
                  "col_offset": 9
                },
                "lineno": 6,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def in_operator():
        x = 3 in [1, 3, 5]
        y = "oo" in "food"
        z = True in [1, 2, 3]
        return [x, y, z]
  */
  private static final String inOperatorJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "in_operator",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Compare",
                  "left": {
                    "type": "Constant",
                    "value": 3,
                    "lineno": 2,
                    "col_offset": 6,
                    "typename": "int"
                  },
                  "ops": [
                    {
                      "type": "In"
                    }
                  ],
                  "comparators": [
                    {
                      "type": "List",
                      "elts": [
                        {
                          "type": "Constant",
                          "value": 1,
                          "lineno": 2,
                          "col_offset": 12,
                          "typename": "int"
                        },
                        {
                          "type": "Constant",
                          "value": 3,
                          "lineno": 2,
                          "col_offset": 15,
                          "typename": "int"
                        },
                        {
                          "type": "Constant",
                          "value": 5,
                          "lineno": 2,
                          "col_offset": 18,
                          "typename": "int"
                        }
                      ],
                      "lineno": 2,
                      "col_offset": 11
                    }
                  ],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "y",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Compare",
                  "left": {
                    "type": "Constant",
                    "value": "oo",
                    "lineno": 3,
                    "col_offset": 6,
                    "typename": "str"
                  },
                  "ops": [
                    {
                      "type": "In"
                    }
                  ],
                  "comparators": [
                    {
                      "type": "Constant",
                      "value": "food",
                      "lineno": 3,
                      "col_offset": 14,
                      "typename": "str"
                    }
                  ],
                  "lineno": 3,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "z",
                    "lineno": 4,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Compare",
                  "left": {
                    "type": "Constant",
                    "value": true,
                    "lineno": 4,
                    "col_offset": 6,
                    "typename": "bool"
                  },
                  "ops": [
                    {
                      "type": "In"
                    }
                  ],
                  "comparators": [
                    {
                      "type": "List",
                      "elts": [
                        {
                          "type": "Constant",
                          "value": 1,
                          "lineno": 4,
                          "col_offset": 15,
                          "typename": "int"
                        },
                        {
                          "type": "Constant",
                          "value": 2,
                          "lineno": 4,
                          "col_offset": 18,
                          "typename": "int"
                        },
                        {
                          "type": "Constant",
                          "value": 3,
                          "lineno": 4,
                          "col_offset": 21,
                          "typename": "int"
                        }
                      ],
                      "lineno": 4,
                      "col_offset": 14
                    }
                  ],
                  "lineno": 4,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Name",
                      "id": "x",
                      "lineno": 5,
                      "col_offset": 10
                    },
                    {
                      "type": "Name",
                      "id": "y",
                      "lineno": 5,
                      "col_offset": 13
                    },
                    {
                      "type": "Name",
                      "id": "z",
                      "lineno": 5,
                      "col_offset": 16
                    }
                  ],
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def bool_operators():
        x = 3 in [1, 3, 5]
        y = "oo" in "food"
        z = True in [1, 2, 3]
        return [x, y, z]
  */
  private static final String boolOperatorsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "bool_operators",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "BoolOp",
                  "op": {
                    "type": "And"
                  },
                  "values": [
                    {
                      "type": "Constant",
                      "value": 5,
                      "lineno": 2,
                      "col_offset": 6,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 7,
                      "lineno": 2,
                      "col_offset": 12,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": "hello",
                      "lineno": 2,
                      "col_offset": 18,
                      "typename": "str"
                    }
                  ],
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "y",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "BoolOp",
                  "op": {
                    "type": "Or"
                  },
                  "values": [
                    {
                      "type": "Constant",
                      "value": 0,
                      "lineno": 3,
                      "col_offset": 6,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": "",
                      "lineno": 3,
                      "col_offset": 11,
                      "typename": "str"
                    },
                    {
                      "type": "List",
                      "elts": [],
                      "lineno": 3,
                      "col_offset": 17
                    },
                    {
                      "type": "Constant",
                      "value": "False",
                      "lineno": 3,
                      "col_offset": 23,
                      "typename": "str"
                    },
                    {
                      "type": "Constant",
                      "value": "world",
                      "lineno": 3,
                      "col_offset": 34,
                      "typename": "str"
                    },
                    {
                      "type": "Constant",
                      "value": 5,
                      "lineno": 3,
                      "col_offset": 45,
                      "typename": "int"
                    }
                  ],
                  "lineno": 3,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "z",
                    "lineno": 4,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "BoolOp",
                  "op": {
                    "type": "Or"
                  },
                  "values": [
                    {
                      "type": "Constant",
                      "value": "!",
                      "lineno": 4,
                      "col_offset": 6,
                      "typename": "str"
                    },
                    {
                      "type": "Name",
                      "id": "undefined_name_short_circuited",
                      "lineno": 4,
                      "col_offset": 13
                    }
                  ],
                  "lineno": 4,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Name",
                      "id": "x",
                      "lineno": 5,
                      "col_offset": 10
                    },
                    {
                      "type": "Name",
                      "id": "y",
                      "lineno": 5,
                      "col_offset": 13
                    },
                    {
                      "type": "Name",
                      "id": "z",
                      "lineno": 5,
                      "col_offset": 16
                    }
                  ],
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def iterate_dict():
        d1 = {1: "one", 2: "two"}
        d2 = {}
        for k, v in d1.items():
          d2[k] = v
        return d2
  */
  private static final String iterateDictJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "iterate_dict",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "d1"
                  }
                ],
                "value": {
                  "type": "Dict",
                  "keys": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "typename": "int"
                    }
                  ],
                  "values": [
                    {
                      "type": "Constant",
                      "value": "one",
                      "typename": "str"
                    },
                    {
                      "type": "Constant",
                      "value": "two",
                      "typename": "str"
                    }
                  ]
                },
                "type_comment": null
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "d2"
                  }
                ],
                "value": {
                  "type": "Dict",
                  "keys": [],
                  "values": []
                },
                "type_comment": null
              },
              {
                "type": "For",
                "target": {
                  "type": "Tuple",
                  "elts": [
                    {
                      "type": "Name",
                      "id": "k"
                    },
                    {
                      "type": "Name",
                      "id": "v"
                    }
                  ]
                },
                "iter": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "d1"
                    },
                    "attr": "items"
                  },
                  "args": [],
                  "keywords": []
                },
                "body": [
                  {
                    "type": "Assign",
                    "targets": [
                      {
                        "type": "Subscript",
                        "value": {
                          "type": "Name",
                          "id": "d2"
                        },
                        "slice": {
                          "type": "Name",
                          "id": "k"
                        }
                      }
                    ],
                    "value": {
                      "type": "Name",
                      "id": "v"
                    },
                    "type_comment": null
                  }
                ],
                "orelse": [],
                "type_comment": null
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "d2"
                }
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def delete_items():
        l = [1, 2, 3]
        d = {1: "one", 2: "two"}
        del l[0]
        del d[2]
        return len(l), len(d)
  */
  private static final String deleteItemsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "delete_items",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "l"
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 3,
                      "typename": "int"
                    }
                  ]
                },
                "type_comment": null
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "d"
                  }
                ],
                "value": {
                  "type": "Dict",
                  "keys": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "typename": "int"
                    }
                  ],
                  "values": [
                    {
                      "type": "Constant",
                      "value": "one",
                      "typename": "str"
                    },
                    {
                      "type": "Constant",
                      "value": "two",
                      "typename": "str"
                    }
                  ]
                },
                "type_comment": null
              },
              {
                "type": "Delete",
                "targets": [
                  {
                    "type": "Subscript",
                    "value": {
                      "type": "Name",
                      "id": "l"
                    },
                    "slice": {
                      "type": "Constant",
                      "value": 0,
                      "typename": "int"
                    }
                  }
                ]
              },
              {
                "type": "Delete",
                "targets": [
                  {
                    "type": "Subscript",
                    "value": {
                      "type": "Name",
                      "id": "d"
                    },
                    "slice": {
                      "type": "Constant",
                      "value": 2,
                      "typename": "int"
                    }
                  }
                ]
              },
              {
                "type": "Return",
                "value": {
                  "type": "Tuple",
                  "elts": [
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "len"
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "l"
                        }
                      ],
                      "keywords": []
                    },
                    {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "len"
                      },
                      "args": [
                        {
                          "type": "Name",
                          "id": "d"
                        }
                      ],
                      "keywords": []
                    }
                  ]
                }
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def assign_tuple():
        x, y = 1, 2
        return x, y
  */
  private static final String assignTupleJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "assign_tuple",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Tuple",
                    "elts": [
                      {
                        "type": "Name",
                        "id": "x",
                        "lineno": 2,
                        "col_offset": 2
                      },
                      {
                        "type": "Name",
                        "id": "y",
                        "lineno": 2,
                        "col_offset": 5
                      }
                    ],
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Tuple",
                  "elts": [
                    {
                      "type": "Constant",
                      "value": 1,
                      "lineno": 2,
                      "col_offset": 9,
                      "typename": "int"
                    },
                    {
                      "type": "Constant",
                      "value": 2,
                      "lineno": 2,
                      "col_offset": 12,
                      "typename": "int"
                    }
                  ],
                  "lineno": 2,
                  "col_offset": 9
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Tuple",
                  "elts": [
                    {
                      "type": "Name",
                      "id": "x",
                      "lineno": 3,
                      "col_offset": 9
                    },
                    {
                      "type": "Name",
                      "id": "y",
                      "lineno": 3,
                      "col_offset": 12
                    }
                  ],
                  "lineno": 3,
                  "col_offset": 9
                },
                "lineno": 3,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def list_comprehension():
        return [x * 10 for x in range(4) if x > 0 and x < 3]
  */
  private static final String listComprehensionJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "list_comprehension",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Return",
                "value": {
                  "type": "ListComp",
                  "elt": {
                    "type": "BinOp",
                    "left": {
                      "type": "Name",
                      "id": "x",
                      "lineno": 2,
                      "col_offset": 10
                    },
                    "op": {
                      "type": "Mult"
                    },
                    "right": {
                      "type": "Constant",
                      "value": 10,
                      "lineno": 2,
                      "col_offset": 14,
                      "typename": "int"
                    },
                    "lineno": 2,
                    "col_offset": 10
                  },
                  "generators": [
                    {
                      "type": "comprehension",
                      "target": {
                        "type": "Name",
                        "id": "x",
                        "lineno": 2,
                        "col_offset": 21
                      },
                      "iter": {
                        "type": "Call",
                        "func": {
                          "type": "Name",
                          "id": "range",
                          "lineno": 2,
                          "col_offset": 26
                        },
                        "args": [
                          {
                            "type": "Constant",
                            "value": 4,
                            "lineno": 2,
                            "col_offset": 32,
                            "typename": "int"
                          }
                        ],
                        "keywords": [],
                        "lineno": 2,
                        "col_offset": 26
                      },
                      "ifs": [
                        {
                          "type": "BoolOp",
                          "op": {
                            "type": "And"
                          },
                          "values": [
                            {
                              "type": "Compare",
                              "left": {
                                "type": "Name",
                                "id": "x",
                                "lineno": 2,
                                "col_offset": 38
                              },
                              "ops": [
                                {
                                  "type": "Gt"
                                }
                              ],
                              "comparators": [
                                {
                                  "type": "Constant",
                                  "value": 0,
                                  "lineno": 2,
                                  "col_offset": 42,
                                  "typename": "int"
                                }
                              ],
                              "lineno": 2,
                              "col_offset": 38
                            },
                            {
                              "type": "Compare",
                              "left": {
                                "type": "Name",
                                "id": "x",
                                "lineno": 2,
                                "col_offset": 48
                              },
                              "ops": [
                                {
                                  "type": "Lt"
                                }
                              ],
                              "comparators": [
                                {
                                  "type": "Constant",
                                  "value": 3,
                                  "lineno": 2,
                                  "col_offset": 52,
                                  "typename": "int"
                                }
                              ],
                              "lineno": 2,
                              "col_offset": 48
                            }
                          ],
                          "lineno": 2,
                          "col_offset": 38
                        }
                      ],
                      "is_async": 0
                    }
                  ],
                  "lineno": 2,
                  "col_offset": 9
                },
                "lineno": 2,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def if_else_expr():
        x = "foo" if 1 else "bar"
        y = "foo" if 0 else "bar"
        return x + y
  */
  private static final String ifElseExprJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "if_else_expr",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "IfExp",
                  "test": {
                    "type": "Constant",
                    "value": 1,
                    "lineno": 2,
                    "col_offset": 15,
                    "typename": "int"
                  },
                  "body": {
                    "type": "Constant",
                    "value": "foo",
                    "lineno": 2,
                    "col_offset": 6,
                    "typename": "str"
                  },
                  "orelse": {
                    "type": "Constant",
                    "value": "bar",
                    "lineno": 2,
                    "col_offset": 22,
                    "typename": "str"
                  },
                  "lineno": 2,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "y",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "IfExp",
                  "test": {
                    "type": "Constant",
                    "value": 0,
                    "lineno": 3,
                    "col_offset": 15,
                    "typename": "int"
                  },
                  "body": {
                    "type": "Constant",
                    "value": "foo",
                    "lineno": 3,
                    "col_offset": 6,
                    "typename": "str"
                  },
                  "orelse": {
                    "type": "Constant",
                    "value": "bar",
                    "lineno": 3,
                    "col_offset": 22,
                    "typename": "str"
                  },
                  "lineno": 3,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "Name",
                    "id": "x",
                    "lineno": 4,
                    "col_offset": 9
                  },
                  "op": {
                    "type": "Add"
                  },
                  "right": {
                    "type": "Name",
                    "id": "y",
                    "lineno": 4,
                    "col_offset": 13
                  },
                  "lineno": 4,
                  "col_offset": 9
                },
                "lineno": 4,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def lambda_test():
        n = 10
        x = lambda y: n * y
        return x(9)
  */
  private static final String lambdaTestJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "lambda_test",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "n",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Constant",
                  "value": 10,
                  "lineno": 2,
                  "col_offset": 6,
                  "typename": "int"
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Lambda",
                  "args": {
                    "type": "arguments",
                    "posonlyargs": [],
                    "args": [
                      {
                        "type": "arg",
                        "arg": "y",
                        "annotation": null,
                        "type_comment": null,
                        "lineno": 3,
                        "col_offset": 13
                      }
                    ],
                    "vararg": null,
                    "kwonlyargs": [],
                    "kw_defaults": [],
                    "kwarg": null,
                    "defaults": []
                  },
                  "body": {
                    "type": "BinOp",
                    "left": {
                      "type": "Name",
                      "id": "n",
                      "lineno": 3,
                      "col_offset": 16
                    },
                    "op": {
                      "type": "Mult"
                    },
                    "right": {
                      "type": "Name",
                      "id": "y",
                      "lineno": 3,
                      "col_offset": 20
                    },
                    "lineno": 3,
                    "col_offset": 16
                  },
                  "lineno": 3,
                  "col_offset": 6
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "x",
                    "lineno": 4,
                    "col_offset": 9
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 9,
                      "lineno": 4,
                      "col_offset": 11,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 4,
                  "col_offset": 9
                },
                "lineno": 4,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def binary_ops():
        results = []
        results.append(22 / 7)
        results.append(2 ** 8)
        results.append(22 % 7)
        results.append("This is a %s." % "test")
        results.append("This is %d %s." % (1, "test"))
        return results
  */
  private static final String binaryOpsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "binary_ops",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "results",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 2,
                  "col_offset": 12
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "results",
                      "lineno": 3,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 3,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "BinOp",
                      "left": {
                        "type": "Constant",
                        "value": 22,
                        "lineno": 3,
                        "col_offset": 17,
                        "typename": "int"
                      },
                      "op": {
                        "type": "Div"
                      },
                      "right": {
                        "type": "Constant",
                        "value": 7,
                        "lineno": 3,
                        "col_offset": 22,
                        "typename": "int"
                      },
                      "lineno": 3,
                      "col_offset": 17
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 2
                },
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "results",
                      "lineno": 4,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 4,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "BinOp",
                      "left": {
                        "type": "Constant",
                        "value": 2,
                        "lineno": 4,
                        "col_offset": 17,
                        "typename": "int"
                      },
                      "op": {
                        "type": "Pow"
                      },
                      "right": {
                        "type": "Constant",
                        "value": 8,
                        "lineno": 4,
                        "col_offset": 22,
                        "typename": "int"
                      },
                      "lineno": 4,
                      "col_offset": 17
                    }
                  ],
                  "keywords": [],
                  "lineno": 4,
                  "col_offset": 2
                },
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "results",
                      "lineno": 5,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 5,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "BinOp",
                      "left": {
                        "type": "Constant",
                        "value": 22,
                        "lineno": 5,
                        "col_offset": 17,
                        "typename": "int"
                      },
                      "op": {
                        "type": "Mod"
                      },
                      "right": {
                        "type": "Constant",
                        "value": 7,
                        "lineno": 5,
                        "col_offset": 22,
                        "typename": "int"
                      },
                      "lineno": 5,
                      "col_offset": 17
                    }
                  ],
                  "keywords": [],
                  "lineno": 5,
                  "col_offset": 2
                },
                "lineno": 5,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "results",
                      "lineno": 6,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 6,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "BinOp",
                      "left": {
                        "type": "Constant",
                        "value": "This is a %s.",
                        "lineno": 6,
                        "col_offset": 17,
                        "typename": "str"
                      },
                      "op": {
                        "type": "Mod"
                      },
                      "right": {
                        "type": "Constant",
                        "value": "test",
                        "lineno": 6,
                        "col_offset": 35,
                        "typename": "str"
                      },
                      "lineno": 6,
                      "col_offset": 17
                    }
                  ],
                  "keywords": [],
                  "lineno": 6,
                  "col_offset": 2
                },
                "lineno": 6,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "results",
                      "lineno": 7,
                      "col_offset": 2
                    },
                    "attr": "append",
                    "lineno": 7,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "BinOp",
                      "left": {
                        "type": "Constant",
                        "value": "This is %d %s.",
                        "lineno": 7,
                        "col_offset": 17,
                        "typename": "str"
                      },
                      "op": {
                        "type": "Mod"
                      },
                      "right": {
                        "type": "Tuple",
                        "elts": [
                          {
                            "type": "Constant",
                            "value": 1,
                            "lineno": 7,
                            "col_offset": 37,
                            "typename": "int"
                          },
                          {
                            "type": "Constant",
                            "value": "test",
                            "lineno": 7,
                            "col_offset": 40,
                            "typename": "str"
                          }
                        ],
                        "lineno": 7,
                        "col_offset": 36
                      },
                      "lineno": 7,
                      "col_offset": 17
                    }
                  ],
                  "keywords": [],
                  "lineno": 7,
                  "col_offset": 2
                },
                "lineno": 7,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "results",
                  "lineno": 8,
                  "col_offset": 9
                },
                "lineno": 8,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def formatted_string():
        x = 99
        return f"start{x + 1}end"
  */
  private static final String formattedStringJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "formatted_string",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "x",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Constant",
                  "value": 99,
                  "lineno": 2,
                  "col_offset": 6,
                  "typename": "int"
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "JoinedStr",
                  "values": [
                    {
                      "type": "Constant",
                      "value": "start",
                      "lineno": 3,
                      "col_offset": 9,
                      "typename": "str"
                    },
                    {
                      "type": "FormattedValue",
                      "value": {
                        "type": "BinOp",
                        "left": {
                          "type": "Name",
                          "id": "x",
                          "lineno": 3,
                          "col_offset": 17
                        },
                        "op": {
                          "type": "Add"
                        },
                        "right": {
                          "type": "Constant",
                          "value": 1,
                          "lineno": 3,
                          "col_offset": 21,
                          "typename": "int"
                        },
                        "lineno": 3,
                        "col_offset": 17
                      },
                      "conversion": -1,
                      "format_spec": null,
                      "lineno": 3,
                      "col_offset": 9
                    },
                    {
                      "type": "Constant",
                      "value": "end",
                      "lineno": 3,
                      "col_offset": 9,
                      "typename": "str"
                    }
                  ],
                  "lineno": 3,
                  "col_offset": 9
                },
                "lineno": 3,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      Thread = JavaClass("java.lang.Thread")

      def run_thread():
        output = "unassigned"
        def set_output(s):
          nonlocal output
          output = s

        # Implicitly promote lambda to Runnable.
        thread = Thread(lambda: set_output("hello from thread"))
        thread.start()
        thread.join()
        return output
  */
  private static final String ctorParamInterfaceProxyJsonAst =
      """
        {
        "type": "Module",
        "body": [
          {
            "type": "Assign",
            "targets": [
              {
                "type": "Name",
                "id": "Thread",
                "lineno": 1,
                "col_offset": 0
              }
            ],
            "value": {
              "type": "Call",
              "func": {
                "type": "Name",
                "id": "JavaClass",
                "lineno": 1,
                "col_offset": 9
              },
              "args": [
                {
                  "type": "Constant",
                  "value": "java.lang.Thread",
                  "lineno": 1,
                  "col_offset": 19,
                  "typename": "str"
                }
              ],
              "keywords": [],
              "lineno": 1,
              "col_offset": 9
            },
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "run_thread",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "output",
                    "lineno": 4,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Constant",
                  "value": "unassigned",
                  "lineno": 4,
                  "col_offset": 11,
                  "typename": "str"
                },
                "type_comment": null,
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "FunctionDef",
                "name": "set_output",
                "args": {
                  "type": "arguments",
                  "posonlyargs": [],
                  "args": [
                    {
                      "type": "arg",
                      "arg": "s",
                      "annotation": null,
                      "type_comment": null,
                      "lineno": 5,
                      "col_offset": 17
                    }
                  ],
                  "vararg": null,
                  "kwonlyargs": [],
                  "kw_defaults": [],
                  "kwarg": null,
                  "defaults": []
                },
                "body": [
                  {
                    "type": "Nonlocal",
                    "names": [
                      "output"
                    ],
                    "lineno": 6,
                    "col_offset": 4
                  },
                  {
                    "type": "Assign",
                    "targets": [
                      {
                        "type": "Name",
                        "id": "output",
                        "lineno": 7,
                        "col_offset": 4
                      }
                    ],
                    "value": {
                      "type": "Name",
                      "id": "s",
                      "lineno": 7,
                      "col_offset": 13
                    },
                    "type_comment": null,
                    "lineno": 7,
                    "col_offset": 4
                  }
                ],
                "decorator_list": [],
                "returns": null,
                "type_comment": null,
                "lineno": 5,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "thread",
                    "lineno": 10,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "Thread",
                    "lineno": 10,
                    "col_offset": 11
                  },
                  "args": [
                    {
                      "type": "Lambda",
                      "args": {
                        "type": "arguments",
                        "posonlyargs": [],
                        "args": [],
                        "vararg": null,
                        "kwonlyargs": [],
                        "kw_defaults": [],
                        "kwarg": null,
                        "defaults": []
                      },
                      "body": {
                        "type": "Call",
                        "func": {
                          "type": "Name",
                          "id": "set_output",
                          "lineno": 10,
                          "col_offset": 26
                        },
                        "args": [
                          {
                            "type": "Constant",
                            "value": "hello from thread",
                            "lineno": 10,
                            "col_offset": 37,
                            "typename": "str"
                          }
                        ],
                        "keywords": [],
                        "lineno": 10,
                        "col_offset": 26
                      },
                      "lineno": 10,
                      "col_offset": 18
                    }
                  ],
                  "keywords": [],
                  "lineno": 10,
                  "col_offset": 11
                },
                "type_comment": null,
                "lineno": 10,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "thread",
                      "lineno": 11,
                      "col_offset": 2
                    },
                    "attr": "start",
                    "lineno": 11,
                    "col_offset": 2
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 11,
                  "col_offset": 2
                },
                "lineno": 11,
                "col_offset": 2
              },
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "thread",
                      "lineno": 12,
                      "col_offset": 2
                    },
                    "attr": "join",
                    "lineno": 12,
                    "col_offset": 2
                  },
                  "args": [],
                  "keywords": [],
                  "lineno": 12,
                  "col_offset": 2
                },
                "lineno": 12,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "output",
                  "lineno": 13,
                  "col_offset": 9
                },
                "lineno": 13,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 3,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      def exceptions():
        Exception = JavaClass("java.lang.Exception")
        IllegalStateException = JavaClass("java.lang.IllegalStateException")
        IllegalArgumentException = JavaClass("java.lang.IllegalArgumentException")
        output = []
        try:
          raise IllegalArgumentException("Thrown from Python.")
          output.append("This code is unreachable.")
        except IllegalStateException as e:
          output.append("Mismatched exception")
        except Exception as e:
          output.append(f"Handled exception: {e}")
        finally:
          output.append("Finally!")
        return output
  */
  private static final String exceptionsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "FunctionDef",
            "name": "exceptions",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "Exception",
                    "lineno": 2,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "JavaClass",
                    "lineno": 2,
                    "col_offset": 14
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "java.lang.Exception",
                      "lineno": 2,
                      "col_offset": 24,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 2,
                  "col_offset": 14
                },
                "type_comment": null,
                "lineno": 2,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "IllegalStateException",
                    "lineno": 3,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "JavaClass",
                    "lineno": 3,
                    "col_offset": 26
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "java.lang.IllegalStateException",
                      "lineno": 3,
                      "col_offset": 36,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 3,
                  "col_offset": 26
                },
                "type_comment": null,
                "lineno": 3,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "IllegalArgumentException",
                    "lineno": 4,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Name",
                    "id": "JavaClass",
                    "lineno": 4,
                    "col_offset": 29
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": "java.lang.IllegalArgumentException",
                      "lineno": 4,
                      "col_offset": 39,
                      "typename": "str"
                    }
                  ],
                  "keywords": [],
                  "lineno": 4,
                  "col_offset": 29
                },
                "type_comment": null,
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Assign",
                "targets": [
                  {
                    "type": "Name",
                    "id": "output",
                    "lineno": 5,
                    "col_offset": 2
                  }
                ],
                "value": {
                  "type": "List",
                  "elts": [],
                  "lineno": 5,
                  "col_offset": 11
                },
                "type_comment": null,
                "lineno": 5,
                "col_offset": 2
              },
              {
                "type": "Try",
                "body": [
                  {
                    "type": "Raise",
                    "exc": {
                      "type": "Call",
                      "func": {
                        "type": "Name",
                        "id": "IllegalArgumentException",
                        "lineno": 7,
                        "col_offset": 10
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": "Thrown from Python.",
                          "lineno": 7,
                          "col_offset": 35,
                          "typename": "str"
                        }
                      ],
                      "keywords": [],
                      "lineno": 7,
                      "col_offset": 10
                    },
                    "cause": null,
                    "lineno": 7,
                    "col_offset": 4
                  },
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "output",
                          "lineno": 8,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 8,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": "This code is unreachable.",
                          "lineno": 8,
                          "col_offset": 18,
                          "typename": "str"
                        }
                      ],
                      "keywords": [],
                      "lineno": 8,
                      "col_offset": 4
                    },
                    "lineno": 8,
                    "col_offset": 4
                  }
                ],
                "handlers": [
                  {
                    "type": {
                      "type": "Name",
                      "id": "IllegalStateException",
                      "lineno": 9,
                      "col_offset": 9
                    },
                    "name": "e",
                    "body": [
                      {
                        "type": "Expr",
                        "value": {
                          "type": "Call",
                          "func": {
                            "type": "Attribute",
                            "value": {
                              "type": "Name",
                              "id": "output",
                              "lineno": 10,
                              "col_offset": 4
                            },
                            "attr": "append",
                            "lineno": 10,
                            "col_offset": 4
                          },
                          "args": [
                            {
                              "type": "Constant",
                              "value": "Mismatched exception",
                              "lineno": 10,
                              "col_offset": 18,
                              "typename": "str"
                            }
                          ],
                          "keywords": [],
                          "lineno": 10,
                          "col_offset": 4
                        },
                        "lineno": 10,
                        "col_offset": 4
                      }
                    ],
                    "lineno": 9,
                    "col_offset": 2
                  },
                  {
                    "type": {
                      "type": "Name",
                      "id": "Exception",
                      "lineno": 11,
                      "col_offset": 9
                    },
                    "name": "e",
                    "body": [
                      {
                        "type": "Expr",
                        "value": {
                          "type": "Call",
                          "func": {
                            "type": "Attribute",
                            "value": {
                              "type": "Name",
                              "id": "output",
                              "lineno": 12,
                              "col_offset": 4
                            },
                            "attr": "append",
                            "lineno": 12,
                            "col_offset": 4
                          },
                          "args": [
                            {
                              "type": "JoinedStr",
                              "values": [
                                {
                                  "type": "Constant",
                                  "value": "Handled exception: ",
                                  "lineno": 12,
                                  "col_offset": 18,
                                  "typename": "str"
                                },
                                {
                                  "type": "FormattedValue",
                                  "value": {
                                    "type": "Name",
                                    "id": "e",
                                    "lineno": 12,
                                    "col_offset": 40
                                  },
                                  "conversion": -1,
                                  "format_spec": null,
                                  "lineno": 12,
                                  "col_offset": 18
                                }
                              ],
                              "lineno": 12,
                              "col_offset": 18
                            }
                          ],
                          "keywords": [],
                          "lineno": 12,
                          "col_offset": 4
                        },
                        "lineno": 12,
                        "col_offset": 4
                      }
                    ],
                    "lineno": 11,
                    "col_offset": 2
                  }
                ],
                "orelse": [],
                "finalbody": [
                  {
                    "type": "Expr",
                    "value": {
                      "type": "Call",
                      "func": {
                        "type": "Attribute",
                        "value": {
                          "type": "Name",
                          "id": "output",
                          "lineno": 14,
                          "col_offset": 4
                        },
                        "attr": "append",
                        "lineno": 14,
                        "col_offset": 4
                      },
                      "args": [
                        {
                          "type": "Constant",
                          "value": "Finally!",
                          "lineno": 14,
                          "col_offset": 18,
                          "typename": "str"
                        }
                      ],
                      "keywords": [],
                      "lineno": 14,
                      "col_offset": 4
                    },
                    "lineno": 14,
                    "col_offset": 4
                  }
                ],
                "lineno": 6,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "output",
                  "lineno": 15,
                  "col_offset": 9
                },
                "lineno": 15,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      Math = JavaClass("mapped.M")

      def calc():
        return Math.p + Math.s(9)
  */
  private static final String mappedSymbolsJsonAst =
      """
        {
        "type": "Module",
        "body": [
          {
            "type": "Assign",
            "targets": [
              {
                "type": "Name",
                "id": "Math",
                "lineno": 1,
                "col_offset": 0
              }
            ],
            "value": {
              "type": "Call",
              "func": {
                "type": "Name",
                "id": "JavaClass",
                "lineno": 1,
                "col_offset": 7
              },
              "args": [
                {
                  "type": "Constant",
                  "value": "mapped.M",
                  "lineno": 1,
                  "col_offset": 17,
                  "typename": "str"
                }
              ],
              "keywords": [],
              "lineno": 1,
              "col_offset": 7
            },
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "calc",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Return",
                "value": {
                  "type": "BinOp",
                  "left": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "Math",
                      "lineno": 4,
                      "col_offset": 9
                    },
                    "attr": "p",
                    "lineno": 4,
                    "col_offset": 9
                  },
                  "op": {
                    "type": "Add"
                  },
                  "right": {
                    "type": "Call",
                    "func": {
                      "type": "Attribute",
                      "value": {
                        "type": "Name",
                        "id": "Math",
                        "lineno": 4,
                        "col_offset": 18
                      },
                      "attr": "s",
                      "lineno": 4,
                      "col_offset": 18
                    },
                    "args": [
                      {
                        "type": "Constant",
                        "value": 9,
                        "lineno": 4,
                        "col_offset": 25,
                        "typename": "int"
                      }
                    ],
                    "keywords": [],
                    "lineno": 4,
                    "col_offset": 18
                  },
                  "lineno": 4,
                  "col_offset": 9
                },
                "lineno": 4,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 3,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  /* Generated from Python code:

      Thread = JavaClass("java.lang.Thread")

      def sleep_func(x):
        Thread.sleep(5000)
        return x
  */
  private static final String threadsJsonAst =
      """
      {
        "type": "Module",
        "body": [
          {
            "type": "Assign",
            "targets": [
              {
                "type": "Name",
                "id": "Thread",
                "lineno": 1,
                "col_offset": 0
              }
            ],
            "value": {
              "type": "Call",
              "func": {
                "type": "Name",
                "id": "JavaClass",
                "lineno": 1,
                "col_offset": 9
              },
              "args": [
                {
                  "type": "Constant",
                  "value": "java.lang.Thread",
                  "lineno": 1,
                  "col_offset": 19,
                  "typename": "str"
                }
              ],
              "keywords": [],
              "lineno": 1,
              "col_offset": 9
            },
            "type_comment": null,
            "lineno": 1,
            "col_offset": 0
          },
          {
            "type": "FunctionDef",
            "name": "sleep_func",
            "args": {
              "type": "arguments",
              "posonlyargs": [],
              "args": [
                {
                  "type": "arg",
                  "arg": "x",
                  "annotation": null,
                  "type_comment": null,
                  "lineno": 3,
                  "col_offset": 15
                }
              ],
              "vararg": null,
              "kwonlyargs": [],
              "kw_defaults": [],
              "kwarg": null,
              "defaults": []
            },
            "body": [
              {
                "type": "Expr",
                "value": {
                  "type": "Call",
                  "func": {
                    "type": "Attribute",
                    "value": {
                      "type": "Name",
                      "id": "Thread",
                      "lineno": 4,
                      "col_offset": 2
                    },
                    "attr": "sleep",
                    "lineno": 4,
                    "col_offset": 2
                  },
                  "args": [
                    {
                      "type": "Constant",
                      "value": 5000,
                      "lineno": 4,
                      "col_offset": 15,
                      "typename": "int"
                    }
                  ],
                  "keywords": [],
                  "lineno": 4,
                  "col_offset": 2
                },
                "lineno": 4,
                "col_offset": 2
              },
              {
                "type": "Return",
                "value": {
                  "type": "Name",
                  "id": "x",
                  "lineno": 5,
                  "col_offset": 9
                },
                "lineno": 5,
                "col_offset": 2
              }
            ],
            "decorator_list": [],
            "returns": null,
            "type_comment": null,
            "lineno": 3,
            "col_offset": 0
          }
        ],
        "type_ignores": []
      }
      """;

  @Test
  public void timesTwo() {
    double x = Math.PI;

    var jsonAst = JsonParser.parseString(timesTwoJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("times_two");
    System.out.println(func);

    var output = func.call(script.mainModule().globals(), x);
    assertEquals(2 * Math.PI, ((Number) output).doubleValue(), 0.000000001);
  }

  @Test
  public void populateArray() {
    String[] array = new String[3];
    int index = 0;
    String value = "first";

    var jsonAst = JsonParser.parseString(populateArrayJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("populate_array");
    System.out.println(func);

    var output = func.call(script.mainModule().globals(), array, index, value);
    assertArrayEquals(new String[] {"first", null, null}, (String[]) output);
  }

  @Test
  public void typeConversions() {
    var jsonAst = JsonParser.parseString(typeConversionsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("type_conversions");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("False2.3", (String) output);
  }

  @Test
  public void incrementGlobal() {
    var jsonAst = JsonParser.parseString(incrementGlobalJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("increment_global");
    System.out.println(func);

    // Execute global statement to define global var: `x = 0`
    script.exec();

    var output = func.call(script.mainModule().globals());
    assertEquals(Integer.valueOf(2), (Integer) output);
  }

  @Test
  public void factorial() {
    var jsonAst = JsonParser.parseString(factorialJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("factorial");
    System.out.println(func);

    var output = func.call(script.mainModule().globals(), 5);
    assertEquals(Integer.valueOf(120), (Integer) output);
  }

  @Test
  public void sqrt9() {
    var jsonAst = JsonParser.parseString(sqrt9JsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("sqrt9");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(3., ((Number) output).doubleValue(), 0.000000001);
  }

  @Test
  public void nestedFuncVars() {
    var jsonAst = JsonParser.parseString(nestedFuncVarsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("nested_func_vars");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("baz(xyz), bar(xy), foo(x)", (String) output);
  }

  @Test
  public void callSiblingNestedFunc() {
    var jsonAst = JsonParser.parseString(callSiblingNestedFuncJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("call_sibling_nested_func");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("bar", (String) output);
  }

  @Test
  public void listOps() {
    var jsonAst = JsonParser.parseString(listOpsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("list_ops");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(101, 2, 3, "bar")), output);
  }

  @Test
  public void ctorAndMethodOverloads() {
    var jsonAst = JsonParser.parseString(ctorAndMethodOverloadsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("ctor_and_method_overloads");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("This is 1 test.", (String) output);
  }

  @Test
  public void rangeStop() {
    var jsonAst = JsonParser.parseString(rangeStopJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("range_stop");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(0, 1, 2)), output);
  }

  @Test
  public void rangeStartStopStep() {
    var jsonAst = JsonParser.parseString(rangeStartStopStepJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("range_start_stop_step");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(4, 6, 8)), output);
  }

  @Test
  public void breakForLoop() {
    var jsonAst = JsonParser.parseString(breakForLoopJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("break_for_loop");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(0, 1)), output);
  }

  @Test
  public void numericTypes() {
    var jsonAst = JsonParser.parseString(numericTypesJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("numeric_types");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of("Integer", "Long", "Float", "Double")), output);
  }

  @Test
  public void whileLoop() {
    var jsonAst = JsonParser.parseString(whileLoopJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("while_loop");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(0, 1, 2)), output);
  }

  @Test
  public void breakWhileLoop() {
    var jsonAst = JsonParser.parseString(breakWhileLoopJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("break_while_loop");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(1)), output);
  }

  @Test
  public void inOperator() {
    var jsonAst = JsonParser.parseString(inOperatorJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("in_operator");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(true, true, false)), output);
  }

  @Test
  public void boolOperators() {
    var jsonAst = JsonParser.parseString(boolOperatorsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("bool_operators");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of("hello", "world", "!")), output);
  }

  @Test
  public void iterateDict() {
    var jsonAst = JsonParser.parseString(iterateDictJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("iterate_dict");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjDict(Map.of(1, "one", 2, "two")), output);
  }

  @Test
  public void deleteItems() {
    var jsonAst = JsonParser.parseString(deleteItemsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("delete_items");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjTuple(new Object[] {2, 1}), output);
  }

  @Test
  public void assignTuple() {
    var jsonAst = JsonParser.parseString(assignTupleJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("assign_tuple");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjTuple(new Object[] {1, 2}), output);
  }

  @Test
  public void listComprehension() {
    var jsonAst = JsonParser.parseString(listComprehensionJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("list_comprehension");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(new Script.PyjList(List.of(10, 20)), output);
  }

  @Test
  public void ifElseExpr() {
    var jsonAst = JsonParser.parseString(ifElseExprJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("if_else_expr");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("foobar", output);
  }

  @Test
  public void lambdaTest() {
    var jsonAst = JsonParser.parseString(lambdaTestJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("lambda_test");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(90, output);
  }

  @Test
  public void binaryOps() {
    var jsonAst = JsonParser.parseString(binaryOpsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("binary_ops");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertTrue(output instanceof Script.PyjList);
    var list = ((Script.PyjList) output).getJavaList();
    assertEquals(5, list.size());
    assertEquals(22. / 7., ((Number) list.get(0)).doubleValue(), 0.000000001); // 22 / 7
    assertEquals(256, list.get(1)); // 2 ** 8
    assertEquals(1, list.get(2)); // 22 % 7
    assertEquals("This is a test.", list.get(3)); // "This is a %s." % "test"
    assertEquals("This is 1 test.", list.get(4)); // "This is %d %s." % (1, "test")
  }

  @Test
  public void formattedString() {
    var jsonAst = JsonParser.parseString(formattedStringJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("formatted_string");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("start100end", output);
  }

  @Test
  public void ctorParamInterfacyProxy() {
    var jsonAst = JsonParser.parseString(ctorParamInterfaceProxyJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("run_thread");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals("hello from thread", output);
  }

  @Test
  public void exceptions() {
    var jsonAst = JsonParser.parseString(exceptionsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("exceptions");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(
        new Script.PyjList(
            List.of(
                "Handled exception: java.lang.IllegalArgumentException: Thrown from Python.",
                "Finally!")),
        output);
  }

  @Test
  public void mappedSymbols() {
    var jsonAst = JsonParser.parseString(mappedSymbolsJsonAst);
    var script =
        new Script(
            "script_test.pyj",
            ClassLoader.getSystemClassLoader(),
            new Script.ModuleHandler() {},
            className -> className.equals("mapped.M") ? "java.lang.Math" : className,
            /* toPrettyClassName= */ name -> name,
            (clazz, fieldName) -> clazz == Math.class && fieldName.equals("p") ? "PI" : fieldName,
            (clazz, methodName) ->
                Set.of(clazz == Math.class && methodName.equals("s") ? "sqrt" : methodName));
    var func = script.parse(jsonAst).exec().getFunction("calc");
    System.out.println(func);

    var output = func.call(script.mainModule().globals());
    assertEquals(6.141592653589793, ((Number) output).doubleValue(), 0.000000001);
  }

  private Script script;
  private Script.Environment env;

  @Test
  public void strMethods() throws Exception {
    env = execute("output = 'foobarbaz'.startswith('foo')");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('foo', 1)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('ba', 3, 6)");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.startswith('az', 3, 6)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('baz')");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('baz', 8)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('ar', 3, 6)");
    assertTrue((Boolean) env.get("output"));

    env = execute("output = 'foobarbaz'.endswith('ba', 3, 6)");
    assertFalse((Boolean) env.get("output"));

    env = execute("output = 'FooBar'.upper()");
    assertEquals("FOOBAR", (String) env.get("output"));

    env = execute("output = 'FooBar'.lower()");
    assertEquals("foobar", (String) env.get("output"));

    env = execute("output = '::'.join(['foo', 'bar', 'baz'])");
    assertEquals("foo::bar::baz", env.get("output"));

    env = execute("output = 'foo\\tbar   baz  '.split()");
    assertArrayEquals(new String[] {"foo", "bar", "baz"}, (String[]) env.get("output"));

    env = execute("output = 'foo[bar]'.split('[')");
    assertArrayEquals(new String[] {"foo", "bar]"}, (String[]) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.strip()");
    assertEquals("foo", (String) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.lstrip()");
    assertEquals("foo \n", (String) env.get("output"));

    env = execute("output = ' \\tfoo \\n'.rstrip()");
    assertEquals(" \tfoo", (String) env.get("output"));

    env = execute("output = 'abfoocd'.strip('abcd')");
    assertEquals("foo", (String) env.get("output"));

    env = execute("output = 'abfoocd'.lstrip('abcd')");
    assertEquals("foocd", (String) env.get("output"));

    env = execute("output = 'abfoocd'.rstrip('abcd')");
    assertEquals("abfoo", (String) env.get("output"));

    env = execute("output = 'foobarbaz'.find('bar')");
    assertEquals(3, (Integer) env.get("output"));

    env = execute("output = 'foo'.find('bar')");
    assertEquals(-1, (Integer) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--')");
    assertEquals("of--b--z--", (String) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--', -1)");
    assertEquals("of--b--z--", (String) env.get("output"));

    env = execute("output = 'ofooboozoo'.replace('oo', '--', 2)");
    assertEquals("of--b--zoo", (String) env.get("output"));
  }

  @Test
  public void sum() throws Exception {
    env = execute("output = sum(())");
    assertEquals(0, (Integer) env.get("output"));

    env = execute("output = sum((), 42)");
    assertEquals(42, (Integer) env.get("output"));

    env = execute("output = sum([1, 4, 9])");
    assertEquals(14, (Integer) env.get("output"));

    env = execute("output = sum([1, 4, 9], 100)");
    assertEquals(114, (Integer) env.get("output"));
  }

  @Test
  public void javaString() throws Exception {
    // str.split() separator interpreted as a literal string
    env = execute("output = 'foo[xyz]bar'.split('[xyz]')");
    assertArrayEquals(new String[] {"foo", "bar"}, (String[]) env.get("output"));

    // String.split() separator interpreted as a regex
    env = execute("output = JavaString('foo[xyz]bar').split('[xyz]')");
    assertArrayEquals(new String[] {"foo[", "", "", "]bar"}, (String[]) env.get("output"));
  }

  @Test
  public void functionConversionToInterface() throws Exception {
    env =
        execute(
            """
            called = False
            def set_called():
              global called
              called = True

            Runnable = JavaClass("java.lang.Runnable")
            runnable = Runnable(set_called)
            runnable.run()
            """);
    assertTrue((Boolean) env.get("called"));
  }

  public interface NestedInterface {
    void doSomething();
  }

  @Test
  public void functionConversionToNestedInterface() throws Exception {
    env =
        execute(
            """
            called = False
            def set_called():
              global called
              called = True

            ScriptTest = JavaClass("org.pyjinn.interpreter.ScriptTest")
            nested = ScriptTest.NestedInterface(set_called)
            nested.doSomething()
            """);
    assertTrue((Boolean) env.get("called"));
  }

  public record NestedClass(String foo) {}

  @Test
  public void nestedClassCtor() throws Exception {
    env =
        execute(
            """
            ScriptTest = JavaClass("org.pyjinn.interpreter.ScriptTest")
            nested = ScriptTest.NestedClass("hello")
            output = nested.foo()
            """);
    assertEquals("hello", (String) env.get("output"));
  }

  @Test
  public void varargs() throws Exception {
    env =
        execute(
            """
            args_array = None

            def foo(*args):
              global args_array
              args_array = JavaArray(args)

            foo("foo", "bar")
            """);

    assertArrayEquals(new Object[] {"foo", "bar"}, (Object[]) env.get("args_array"));
  }

  @Test
  public void packKeywordArgs() throws Exception {
    env =
        execute(
            """
            a = None
            b = None
            c = None

            def foo(x, y, z):
              global a, b, c
              a = x
              b = y
              c = z

            args = {"x": "first", "y": "second", "z": "third"}
            foo(**args)
            """);

    assertEquals("first", env.get("a"));
    assertEquals("second", env.get("b"));
    assertEquals("third", env.get("c"));
  }

  @Test
  public void unpackKeywordArgs() throws Exception {
    env =
        execute(
            """
            kwargs = None

            def foo(**kw):
              global kwargs
              kwargs = kw

            foo(x=0, y=1, z=2)
            """);

    var kwargs = env.get("kwargs");
    assertNotNull(kwargs);
    assertEquals(Script.PyjDict.class, kwargs.getClass());
    var dict = (Script.PyjDict) kwargs;
    assertEquals(3, dict.__len__());
    assertEquals(0, dict.get("x"));
    assertEquals(1, dict.get("y"));
    assertEquals(2, dict.get("z"));
  }

  @Test
  public void unpackKeywordArgsAfterNamedArgs() throws Exception {
    env =
        execute(
            """
            gx = None
            gy = None
            kwargs = None

            def foo(x, y, **kw):
              global gx, gy, kwargs
              gx = x
              gy = y
              kwargs = kw

            foo(x=0, y=1, z=2)
            """);

    assertEquals(0, env.get("gx"));
    assertEquals(1, env.get("gy"));

    var kwargs = env.get("kwargs");
    assertNotNull(kwargs);
    assertEquals(Script.PyjDict.class, kwargs.getClass());
    var dict = (Script.PyjDict) kwargs;
    assertEquals(1, dict.__len__());
    assertEquals(2, dict.get("z"));
  }

  @Test
  public void dictEmpty() throws Exception {
    env = execute("output = dict()");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(0, dict.__len__());
  }

  @Test
  public void dictFromKeywords() throws Exception {
    env = execute("output = dict(x=1, y=2)");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(2, dict.__len__());
    assertEquals(1, dict.get("x"));
    assertEquals(2, dict.get("y"));
  }

  @Test
  public void dictFromIterablePairs() throws Exception {
    env = execute("output = dict([(1, 2), (3, 4)])");
    var output = env.get("output");
    assertNotNull(output);
    assertEquals(Script.PyjDict.class, output.getClass());
    var dict = (Script.PyjDict) output;
    assertEquals(2, dict.__len__());
    assertEquals(2, dict.get(1));
    assertEquals(4, dict.get(3));
  }

  @Test
  public void dictCopy() throws Exception {
    env =
        execute(
            """
            d1 = dict(x=0, y=1)
            d2 = dict(d1)
            d2["z"] = "three"
            """);
    var d1 = env.get("d1");
    assertNotNull(d1);
    assertEquals(Script.PyjDict.class, d1.getClass());
    var dict1 = (Script.PyjDict) d1;

    var d2 = env.get("d2");
    assertNotNull(d2);
    assertEquals(Script.PyjDict.class, d2.getClass());
    var dict2 = (Script.PyjDict) d2;

    assertEquals(2, dict1.__len__());
    assertEquals(3, dict2.__len__());
  }

  @Test
  public void floorDivision() throws Exception {
    env =
        execute(
            """
            a = 11 // 2
            b = 11.0 // 2
            c = -11 // 2
            d = 11 // -2
            e = -11 // -2
            f = -11.0 // 2
            """);

    assertVariableValue(5, "a");
    assertVariableValue(5.0, "b");
    assertVariableValue(-6, "c");
    assertVariableValue(-6, "d");
    assertVariableValue(5, "e");
    assertVariableValue(-6.0, "f");
  }

  @Test
  public void registerAtExit() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(message):
          array[0] = message

        __atexit_register__(on_exit, "finished")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertArrayEquals(new Object[] {"finished"}, array);
  }

  @Test
  public void registerAtExitWithKeywordArgs() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(**args):
          array[0] = args

        __atexit_register__(on_exit, x="foo", y="bar")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertEquals(1, array.length);
    assertNotNull(array[0]);
    assertEquals(Script.PyjDict.class, array[0].getClass());

    var dict = (Script.PyjDict) array[0];
    assertEquals(2, dict.__len__());
    assertEquals("foo", dict.get("x"));
    assertEquals("bar", dict.get("y"));
  }

  @Test
  public void registerAtExitWithVarArgsAndKeywordArgs() throws Exception {
    execute(
        """
        array = JavaArray((None, None))

        def on_exit(*args, **kwargs):
          array[0] = args
          array[1] = kwargs

        __atexit_register__(on_exit, 99, 100, x="foo", y="bar")
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null, null}, array);

    script.exit();
    assertEquals(2, array.length);

    assertNotNull(array[0]);
    assertEquals(Script.PyjTuple.class, array[0].getClass());
    var args = (Script.PyjTuple) array[0];
    assertEquals(2, args.__len__());
    assertEquals(99, args.__getitem__(0));
    assertEquals(100, args.__getitem__(1));

    assertNotNull(array[1]);
    assertEquals(Script.PyjDict.class, array[1].getClass());
    var kwargs = (Script.PyjDict) array[1];
    assertEquals(2, kwargs.__len__());
    assertEquals("foo", kwargs.get("x"));
    assertEquals("bar", kwargs.get("y"));
  }

  @Test
  public void unregisterAtExit() throws Exception {
    execute(
        """
        array = JavaArray((None,))

        def on_exit(message):
          array[0] = message

        __atexit_register__(on_exit, "finished")
        __atexit_unregister__(on_exit)
        """);

    var array = getVariable(Object[].class, "array");
    assertArrayEquals(new Object[] {null}, array);

    script.exit();
    assertArrayEquals(new Object[] {null}, array);
  }

  @Test
  public void varargExpression() throws Exception {
    execute("output = int(*[3.14])");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void emptyVarargs() throws Exception {
    execute("output = int(3.14, *[])");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void emptyKeywordArgs() throws Exception {
    // Verifies that `**{}` doesn't produce an additional kwargs param to params to
    // Script.Function.call(env, params).
    execute("output = int(3.14, **{})");
    assertEquals(3, getVariable(Integer.class, "output"));
  }

  @Test
  public void tupleConstructor() throws Exception {
    execute(
        """
        x = type((1, 2, 3))
        y = type(x([1, 2, 3]))
        z = type(tuple([1, 2, 3]))
        """);
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjTuple.class, getVariable(JavaClass.class, "tuple").type());
  }

  @Test
  public void listConstructor() throws Exception {
    execute(
        """
        x = type([1, 2, 3])
        y = type(x((1, 2, 3)))
        z = type(list((1, 2, 3)))
        """);
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjList.class, getVariable(JavaClass.class, "list").type());
  }

  @Test
  public void dictConstructor() throws Exception {
    execute(
        """
        x = type({1: 2, 3: 4})
        y = type(x([[1, 2], [3, 4]]))
        z = type(dict([[1, 2], [3, 4]]))
        """);
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "x").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "y").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "z").type());
    assertEquals(Script.PyjDict.class, getVariable(JavaClass.class, "dict").type());
  }

  @Test
  public void javaStringConstructor() throws Exception {
    execute(
        """
        String = JavaClass("org.pyjinn.interpreter.JavaString")
        Byte = JavaClass("java.lang.Byte")
        some_bytes = JavaArray(
            (Byte.valueOf("104"),  # 'h'
             Byte.valueOf("101"),  # 'e'
             Byte.valueOf("108"),  # 'l'
             Byte.valueOf("108"),  # 'l'
             Byte.valueOf("111")), # 'o'
            Byte.TYPE)
        output = String(some_bytes)  # String(byte[])
        """);
    assertEquals("hello", getVariable(String.class, "output"));
  }

  @Test
  public void isinstance() throws Exception {
    execute(
        """
        @dataclass
        class Foo:
          x: int = 0

        @dataclass
        class Bar:
          x: int = 0

        foo = Foo()
        test1 = isinstance(foo, Foo)
        test2 = isinstance(foo, Bar)

        none_type = type(None)
        test3 = isinstance(None, none_type)

        test4 = isinstance(0, int)
        test5 = isinstance(0, float)
        test6 = isinstance(True, bool)
        test7 = isinstance(True, str)
        test8 = isinstance("foo", str)
        test9 = isinstance("foo", Foo)
        test10 = isinstance(None, str)
        """);
    assertEquals("NoneType", getVariable(Script.PyjClass.class, "none_type").name);
    assertTrue(getVariable(Boolean.class, "test1"));
    assertFalse(getVariable(Boolean.class, "test2"));
    assertTrue(getVariable(Boolean.class, "test3"));
    assertTrue(getVariable(Boolean.class, "test4"));
    assertFalse(getVariable(Boolean.class, "test5"));
    assertTrue(getVariable(Boolean.class, "test6"));
    assertFalse(getVariable(Boolean.class, "test7"));
    assertTrue(getVariable(Boolean.class, "test8"));
    assertFalse(getVariable(Boolean.class, "test9"));
    assertFalse(getVariable(Boolean.class, "test10"));
  }

  @Test
  public void addTuples() throws Exception {
    execute("output = (2, 4) + (6, 8)");
    var tuple = getVariable(Script.PyjTuple.class, "output");
    assertEquals(4, tuple.__len__());
    assertEquals(2, tuple.__getitem__(0));
    assertEquals(4, tuple.__getitem__(1));
    assertEquals(6, tuple.__getitem__(2));
    assertEquals(8, tuple.__getitem__(3));
  }

  @Test
  public void starExpansionInTupleLiteral() throws Exception {
    execute("output = (*(2, 4), *(6, 8))");
    var tuple = getVariable(Script.PyjTuple.class, "output");
    assertEquals(4, tuple.__len__());
    assertEquals(2, tuple.__getitem__(0));
    assertEquals(4, tuple.__getitem__(1));
    assertEquals(6, tuple.__getitem__(2));
    assertEquals(8, tuple.__getitem__(3));
  }

  @Test
  public void starExpansionInListLiteral() throws Exception {
    execute("output = [*(2, 4), *[6, 8]]");
    var list = getVariable(Script.PyjList.class, "output");
    assertEquals(4, list.__len__());
    assertEquals(2, list.__getitem__(0));
    assertEquals(4, list.__getitem__(1));
    assertEquals(6, list.__getitem__(2));
    assertEquals(8, list.__getitem__(3));
  }

  private <T> T getVariable(Class<T> clazz, String variableName) {
    Object object = env.get(variableName);
    assertNotNull(object);
    assertInstanceOf(clazz, object);
    return clazz.cast(object);
  }

  private void assertVariableValue(Object expectedValue, String variableName) {
    Object object = env.get(variableName);
    assertNotNull(object);
    assertEquals(expectedValue, object);
  }

  @Test
  public void threads() throws InterruptedException, ExecutionException {
    var jsonAst = JsonParser.parseString(threadsJsonAst);
    var script = new Script();
    var func = script.parse(jsonAst).exec().getFunction("sleep_func");
    System.out.println(func);

    Callable<Integer> task1 = () -> (Integer) func.call(script.mainModule().globals(), 3);
    Callable<Integer> task2 = () -> (Integer) func.call(script.mainModule().globals(), 4);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<Integer> future1 = executor.submit(task1);
    Future<Integer> future2 = executor.submit(task2);
    Integer result1 = future1.get();
    Integer result2 = future2.get();
    int totalSum = result1 + result2;
    executor.shutdown();

    assertEquals(7, totalSum);
  }

  private Script.Environment execute(String source) throws Exception {
    var jsonAst = PyjinnParser.parse("script_test.pyj", source);
    script = new Script();
    script.parse(jsonAst).exec();
    env = script.mainModule().globals();
    return env;
  }

  // TODO(maxuser): Add tests for:
  // - classes
  // - dataclasses (mutable @dataclass, immutable @dataclass(Frozen=True))
  // - classes with custom __init__ method
  // - assignment to class instance fields
  // - assignment to class-level fields
  // - calling instance methods
  // - calling static methods
  // - calling class methods
  // - indexing string, e.g. "foo"[0] == "f"
  // - dict methods: keys(), values(), setdefault(key, default=None)
  // - iterability of strings with for/list()/tuple()/enumerate()
  // - new built-in functions: abs, round, min, max, ord, chr, enumerate
  // - raising Python-defined exception types
  // - catching exceptions without a declared type or variable name
  // - __getitem__ operator with slices: items[lower:upper:step] (step not implemented)
  // - slice notation for Java List, String, and array types
  // - `is`, `is not`, and `not in` binary in-fix operators
  // - tuple and list constructors which take no params or String, array, or Iterable<?>
  // - tuple assignment from Java array
  // - enforce immutability of tuples (unless Java array is explicitly accessed)
  // - fix bug where -= was behaving like +=
  // - passing script functions to Java methods taking functional params like Predicate<T>
  // - when checking methods of a non-public class, search for a public interface or superclass
  // - bound Python methods, e.g. `f = obj.func` where obj.func(...) is invoked by f(...)
  // - AugAssign for field setters of PyObject, e.g. `self.x += 1`
  // - empty Java array treated as False in a bool context
  // - function defs and calls with default args and kwargs

  // TODO(maxuser): Implement parity for most common str methods
}
