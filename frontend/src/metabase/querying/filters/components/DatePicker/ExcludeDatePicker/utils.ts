import dayjs from "dayjs";
import { t } from "ttag";
import _ from "underscore";

import type {
  DatePickerExtractionUnit,
  DatePickerOperator,
  DatePickerUnit,
  ExcludeDatePickerOperator,
  ExcludeDatePickerValue,
} from "metabase/querying/filters/types";

import { EXCLUDE_OPERATOR_OPTIONS, EXCLUDE_UNIT_OPTIONS } from "./constants";
import type {
  ExcludeOperatorOption,
  ExcludeUnitOption,
  ExcludeValueOption,
} from "./types";

export function getExcludeUnitOptions(
  availableOperators: DatePickerOperator[],
  availableUnits: DatePickerUnit[],
): ExcludeUnitOption[] {
  if (!availableOperators.includes("!=")) {
    return [];
  }

  return EXCLUDE_UNIT_OPTIONS.filter((option) =>
    availableUnits.includes(option.unit),
  );
}

export function getExcludeOperatorOptions(
  availableOperators: DatePickerOperator[],
): ExcludeOperatorOption[] {
  return EXCLUDE_OPERATOR_OPTIONS.filter((option) =>
    availableOperators.includes(option.operator),
  );
}

export function findExcludeUnitOption(
  unit: DatePickerExtractionUnit,
): ExcludeUnitOption | undefined {
  return EXCLUDE_UNIT_OPTIONS.find((option) => option.unit === unit);
}

export function getExcludeValueOptionGroups(
  unit: DatePickerExtractionUnit,
): ExcludeValueOption[][] {
  switch (unit) {
    case "hour-of-day":
      return [
        _.range(0, 12).map(getExcludeHourOption),
        _.range(12, 24).map(getExcludeHourOption),
      ];
    case "day-of-week":
      return [_.range(1, 8).map(getExcludeDayOption)];
    case "month-of-year":
      return [
        _.range(1, 7).map(getExcludeMonthOption),
        _.range(7, 13).map(getExcludeMonthOption),
      ];
    case "quarter-of-year":
      return [getExcludeQuarterOptions()];
  }
}

function getExcludeHourOption(hour: number): ExcludeValueOption {
  const date = dayjs().hour(hour);
  return { value: hour, label: date.format("h A") };
}

function getExcludeDayOption(day: number): ExcludeValueOption {
  const date = dayjs().isoWeekday(day);
  return { value: day, label: date.format("dddd") };
}

function getExcludeMonthOption(month: number): ExcludeValueOption {
  // month is 1-12, but dayjs accepts 0-11
  const date = dayjs().month(month - 1);
  return { value: month, label: date.format("MMMM") };
}

function getExcludeQuarterOptions(): ExcludeValueOption[] {
  return [
    { value: 1, label: t`1st` },
    { value: 2, label: t`2nd` },
    { value: 3, label: t`3rd` },
    { value: 4, label: t`4th` },
  ];
}

export function getExcludeOperatorValue(
  operator: ExcludeDatePickerOperator,
): ExcludeDatePickerValue {
  return {
    type: "exclude",
    operator,
    values: [],
  };
}

export function getExcludeUnitValue(
  unit: DatePickerExtractionUnit,
  values: number[],
): ExcludeDatePickerValue {
  return {
    type: "exclude",
    operator: "!=",
    unit,
    values,
  };
}
