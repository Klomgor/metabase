---
title: Offset
---

# Offset

> ⚠️ The `Offset` function is currently unavailable for MySQL/MariaDB, ClickHouse, MongoDB, and Druid.

The `Offset` function returns the value of an expression in a different row. `Offset` can only be used in the query builder's Summarize step (you cannot use `Offset` to create a custom column).

Syntax: `Offset(expression, rowOffset)`

The `expression` is the value to get from a different row.

The `rowOffset` is the number relative to the current row. For example, `-1` for the previous row, or `1` for the next row.

Example: `Offset(Sum([Total]), -1)` would get the value of `Sum([Total])` from the previous row.

## The order of the breakouts matter

Because `Offset` refers to other rows, the order of the breakouts matters (the breakouts are the groups in the "Group By" section in the Summarization step). Metabase will sort by the first group, then partition by any additional breakouts. For example, if you want to see the counts of orders by product category over time, and the counts by product category for the previous period, you should first group by `Created At`, then by the product category.

## Offset doesn't account for missing data

`Offset` can only reference the rows you _have_, which means it can produce correct but unexpected results. For example, let's say you want to compare each _day's_ count to the previous day.

Take the following table, where the `Previous` column is created by the expression `Offset(Count, -1)`:

| Date     | Count | Previous |
| -------- | ----- | -------- |
| 1-Oct-22 | 6     |          |
| 2-Oct-22 | 3     | 6        |
| 4-Oct-22 | 2     | 3        |

The value in the `Previous` column for October 4th is `3`, which is the value of the previous row. While correct based on the data, the previous row is October _2nd_; there's no data for the previous _day_, October 3rd. If you want to use offset to compare previous _days_ (or weeks, or whatever), you need to make sure that your data includes a row for each data point you want to compare. In this case, your data would need to include a row for each day, including days where the count was zero. If you're missing dates in your data, you could join a calendar table to your data to make sure that each day has a row in your data.

## Data types

The `Offset` function returns whatever value is in the offset row.

| [Data type](https://www.metabase.com/learn/grow-your-data-skills/data-fundamentals/data-types-overview#examples-of-data-types) | Returned by `Offset` |
| ------------------------------------------------------------------------------------------------------------------------------ | -------------------- |
| String                                                                                                                         | ✅                   |
| Number                                                                                                                         | ✅                   |
| Timestamp                                                                                                                      | ✅                   |
| Boolean                                                                                                                        | ✅                   |
| JSON                                                                                                                           | ✅                   |

## Example year-over-year (YoY) time series comparison using `Offset`

In the Sample database, you can use `Offset` to compare the count of orders year over year (YoY).

First, summarize by Sum of Total. Then summarize that summation again, this time using `Offset` to grab the previous row's value.

```
Offset(Sum([Total]), -1)
```

Then group the results by `Created At` by year:

![Comparing year over year](../../images/sum-of-totals-for-previous-period.png)

Which yields:

![Year over year order sum of order totals](../../images/year-over-year-sum-totals.png)

With these offsets (the Sums in the "Previous period" column), we can then create [custom columns](../editor.md#custom-columns) to calculate things like the difference between yearly Sums:

```
[Sum of total] - [Previous period]
```

And the percentage change year to year:

```
[Difference] / [Previous period] * 100
```

![Difference and percentage change](../../images/diff-and-percentage.png)

## Example rolling average using `Offset`

You can use a custom expression with `Offset` to calculate rolling averages.

For example, let's say you want to calculate the rolling average sum of order totals over the past three months. You could create a custom expression to calculate these rolling averages:

```
(Sum([Total]) + Offset(Sum([Total]), -1) + Offset(Sum([Total]), -2)) / 3
```

The above expression adds up this period's total, plus the totals for the previous two periods (offset by `-1` and `-2`), and then divides by three to get the average across those periods.

![Rolling average](../../images/rolling-average.png)

## Related functions

### SQL

The `Offset` function compares with SQL's `LAG` and `LEAD` window functions.

For example, if you're trying to create a line chart with two series to compare a) this month's order counts with b) the previous month's order counts, you'd `count` the orders for this month, then use an `offset` expression to count the previous month's orders, like so:

```
Offset(count, -1)
```

Under the hood, Metabase will translate that `Offset` expression into a `LAG` window function, like in this query:

```sql
SELECT
  "source"."CREATED_AT" AS "CREATED_AT",
  COUNT(*) AS "count",
  LAG(COUNT(*), 1) OVER (

ORDER BY
      "source"."CREATED_AT" ASC
  ) AS "Order count previous period"
FROM
  (
    SELECT
      DATE_TRUNC('month', "PUBLIC"."ORDERS"."CREATED_AT") AS "CREATED_AT"
    FROM
      "PUBLIC"."ORDERS"
  ) AS "source"
GROUP BY
  "source"."CREATED_AT"
ORDER BY
  "source"."CREATED_AT" ASC
```

## Further reading

- [Custom expressions documentation](../expressions.md)
- [Custom expressions tutorial](https://www.metabase.com/learn/metabase-basics/querying-and-dashboards/questions/custom-expressions)
